import Foundation
import AVFoundation
import Combine
import Shared

/// FT8 音频：发射播放 12kHz PCM16，接收录 15 秒并线性重采样到 12kHz。
///
/// 采样率固定 12kHz 与共享内核 `Ft8Bridge.SAMPLE_RATE` 对齐；
/// 解码（Costas 同步 + LDPC）全部在共享内核完成。
final class Ft8Audio: ObservableObject {

    @Published private(set) var isPlaying = false
    @Published private(set) var isRecording = false

    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private var recorderBuffer: [Float] = []
    private var inputSampleRate: Double = 48_000
    private var recordTimer: Timer?
    private var recordCompletion: ((Data) -> Void)?

    init() {
        engine.attach(player)
        let format = AVAudioFormat(standardFormatWithSampleRate: 12_000, channels: 1)!
        engine.connect(player, to: engine.mainMixerNode, format: format)
    }

    // MARK: - 发射

    func play(pcm: Data) {
        stopPlayback()
        guard !pcm.isEmpty else { return }
        let sampleCount = pcm.count / 2
        guard sampleCount > 0,
              let buffer = AVAudioPCMBuffer(
                pcmFormat: AVAudioFormat(standardFormatWithSampleRate: 12_000, channels: 1)!,
                frameCapacity: AVAudioFrameCount(sampleCount)
              ),
              let channel = buffer.floatChannelData?[0] else { return }
        buffer.frameLength = AVAudioFrameCount(sampleCount)
        pcm.withUnsafeBytes { raw in
            let int16 = raw.bindMemory(to: Int16.self)
            for i in 0..<sampleCount {
                channel[i] = Float(Int16(littleEndian: int16[i])) / 32768.0
            }
        }
        do {
            try AudioSession.activateRecording()
            try engine.start()
        } catch {
            return
        }
        isPlaying = true
        player.scheduleBuffer(buffer, at: nil, options: []) { [weak self] in
            DispatchQueue.main.async {
                self?.isPlaying = false
            }
        }
        player.play()
    }

    func stopPlayback() {
        if player.isPlaying { player.stop() }
        if engine.isRunning { engine.stop() }
        isPlaying = false
    }

    // MARK: - 接收

    /// 录 [seconds] 秒，回调为 12kHz PCM16 小端数据。
    func record(seconds: Double = 15, onComplete: @escaping (Data) -> Void) {
        guard !isRecording else { return }
        recorderBuffer = []
        recordCompletion = onComplete
        isRecording = true

        do {
            try AudioSession.activateRecording()
        } catch {
            isRecording = false
            return
        }

        let input = engine.inputNode
        let hardwareFormat = input.inputFormat(forBus: 0)
        inputSampleRate = hardwareFormat.sampleRate > 0 ? hardwareFormat.sampleRate : 48_000
        input.removeTap(onBus: 0)
        input.installTap(onBus: 0, bufferSize: 4096, format: hardwareFormat) { [weak self] buffer, _ in
            guard let self, let channel = buffer.floatChannelData?[0] else { return }
            let frames = Int(buffer.frameLength)
            self.recorderBuffer.append(contentsOf: UnsafeBufferPointer(start: channel, count: frames))
        }

        engine.prepare()
        do {
            try engine.start()
        } catch {
            input.removeTap(onBus: 0)
            isRecording = false
            return
        }

        recordTimer = Timer.scheduledTimer(withTimeInterval: seconds, repeats: false) { [weak self] _ in
            self?.finishRecording()
        }
    }

    private func finishRecording() {
        recordTimer?.invalidate()
        recordTimer = nil
        engine.inputNode.removeTap(onBus: 0)
        if engine.isRunning { engine.stop() }
        isRecording = false

        let resampled = Self.resample(recorderBuffer, from: inputSampleRate, to: 12_000)
        recorderBuffer = []

        var data = Data(capacity: resampled.count * 2)
        for sample in resampled {
            let clamped = max(-1.0, min(1.0, sample))
            let v = Int16(clamped * 32767.0)
            withUnsafeBytes(of: v.littleEndian) { data.append(contentsOf: $0) }
        }
        let callback = recordCompletion
        recordCompletion = nil
        callback?(data)
    }

    func cancelRecording() {
        recordTimer?.invalidate()
        recordTimer = nil
        engine.inputNode.removeTap(onBus: 0)
        if engine.isRunning { engine.stop() }
        isRecording = false
        recorderBuffer = []
        recordCompletion = nil
    }

    /// 线性插值重采样（FT8 解码对幅度不敏感，简单算法足够）。
    private static func resample(_ samples: [Float], from: Double, to: Double) -> [Float] {
        guard !samples.isEmpty, from > 0, to > 0 else { return [] }
        if abs(from - to) < 1 { return samples }
        let ratio = from / to
        let outCount = Int(Double(samples.count) / ratio)
        var out = [Float](repeating: 0, count: outCount)
        for i in 0..<outCount {
            let pos = Double(i) * ratio
            let index = Int(pos)
            let frac = Float(pos - Double(index))
            let a = samples[min(index, samples.count - 1)]
            let b = samples[min(index + 1, samples.count - 1)]
            out[i] = a + (b - a) * frac
        }
        return out
    }
}

/// FT8 设置：呼号 / 网格 / 频段（对齐 Android `Ft8Config`）。
final class Ft8Store: ObservableObject {

    static let shared = Ft8Store()

    struct Band: Identifiable {
        let id: String
        let freqHz: Int64
    }

    static let bands: [Band] = [
        Band(id: "160m", freqHz: 1_840_000), Band(id: "80m", freqHz: 3_573_000),
        Band(id: "60m", freqHz: 5_357_000), Band(id: "40m", freqHz: 7_074_000),
        Band(id: "30m", freqHz: 10_136_000), Band(id: "20m", freqHz: 14_074_000),
        Band(id: "17m", freqHz: 18_100_000), Band(id: "15m", freqHz: 21_074_000),
        Band(id: "12m", freqHz: 24_915_000), Band(id: "10m", freqHz: 28_074_000),
        Band(id: "6m", freqHz: 50_313_000), Band(id: "2m", freqHz: 144_174_000),
    ]

    @Published var callsign: String { didSet { defaults.set(callsign, forKey: "hamkit.ft8.callsign") } }
    @Published var grid: String { didSet { defaults.set(grid, forKey: "hamkit.ft8.grid") } }
    @Published var band: String { didSet { defaults.set(band, forKey: "hamkit.ft8.band") } }

    private let defaults = UserDefaults.standard

    private init() {
        callsign = defaults.string(forKey: "hamkit.ft8.callsign") ?? ""
        grid = defaults.string(forKey: "hamkit.ft8.grid") ?? ""
        band = defaults.string(forKey: "hamkit.ft8.band") ?? "40m"
    }

    var bandFreqHz: Int64 {
        Self.bands.first { $0.id == band }?.freqHz ?? 7_074_000
    }

    /// 自动通联完成后写入 QSO 日志（标记完成，报告使用默认 -05）。
    func recordAutoQso(target: String, text: String, grid: String) {
        let entry = QsoEntry(
            callsign: target,
            grid: "",
            reportSent: "-05",
            reportReceived: "-05",
            bandName: band,
            freqHz: bandFreqHz,
            mode: "FT8",
            qsoTime: Date(),
            isComplete: true,
            operatorCallsign: callsign,
            myGrid: grid,
            comment: "auto: \(text)"
        )
        QsoStore.shared.upsert(entry)
    }
}
