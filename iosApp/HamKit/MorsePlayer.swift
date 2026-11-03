import Foundation
import AVFoundation
import Combine
import Shared

/// CW 音频播放器：把共享内核产出的「音/停时间线」合成正弦波，用 AVAudioEngine 播放。
///
/// 节拍、点划时长全部由 `MorseCode.timeline` 决定（与 Android AudioTrack 版同源），
/// iOS 只负责 PCM 合成与播放控制。
final class MorsePlayer: ObservableObject {

    @Published private(set) var isPlaying = false

    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private let sampleRate: Double = 44_100
    private var completion: (() -> Void)?

    init() {
        engine.attach(player)
        let format = AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: 1)!
        engine.connect(player, to: engine.mainMixerNode, format: format)
    }

    /// 播放一段摩斯码。完成（或被 stop）后回调。
    func play(morse: String, wpm: Int, frequency: Double, onComplete: (() -> Void)? = nil) {
        stop()

        let steps = MorseCode.shared.timeline(morse: morse, wpm: Int32(wpm))
        guard !steps.isEmpty else {
            onComplete?()
            return
        }

        // 总采样数
        let totalMs = steps.reduce(0) { $0 + Int($1.durationMs) }
        let totalSamples = AVAudioFrameCount(max(1, Int(sampleRate * Double(totalMs) / 1000.0)))
        let format = AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: 1)!
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: totalSamples),
              let channel = buffer.floatChannelData?[0] else {
            onComplete?()
            return
        }
        buffer.frameLength = totalSamples

        var index = 0
        let omega = 2.0 * Double.pi * frequency
        for step in steps {
            let count = max(1, Int(sampleRate * Double(step.durationMs) / 1000.0))
            if step.isTone {
                for i in 0..<count {
                    if index >= Int(totalSamples) { break }
                    channel[index] = Float(sin(omega * Double(i) / sampleRate) * 0.35)
                    index += 1
                }
            } else {
                for _ in 0..<count {
                    if index >= Int(totalSamples) { break }
                    channel[index] = 0
                    index += 1
                }
            }
        }

        do {
            try AudioSession.activatePlayback()
            try engine.start()
        } catch {
            onComplete?()
            return
        }

        completion = onComplete
        isPlaying = true
        player.scheduleBuffer(buffer, at: nil, options: []) { [weak self] in
            DispatchQueue.main.async {
                guard let self else { return }
                self.isPlaying = false
                let callback = self.completion
                self.completion = nil
                callback?()
            }
        }
        player.play()
    }

    func stop() {
        completion = nil
        if player.isPlaying { player.stop() }
        if engine.isRunning { engine.stop() }
        isPlaying = false
    }
}

/// CW 设置（UserDefaults 持久化，对齐 Android `CWSettingsStore` 的字段）。
final class CwSettingsStore: ObservableObject {

    static let shared = CwSettingsStore()

    @Published var wpm: Int {
        didSet { defaults.set(wpm, forKey: Keys.wpm) }
    }
    @Published var toneHz: Int {
        didSet { defaults.set(toneHz, forKey: Keys.tone) }
    }
    @Published var characterSet: String {
        didSet { defaults.set(characterSet, forKey: Keys.charset) }
    }
    @Published var playModeInterval: Bool {
        didSet { defaults.set(playModeInterval, forKey: Keys.interval) }
    }

    private let defaults = UserDefaults.standard
    private enum Keys {
        static let wpm = "hamkit.cw.wpm"
        static let tone = "hamkit.cw.tone_hz"
        static let charset = "hamkit.cw.charset"
        static let interval = "hamkit.cw.interval"
    }

    private init() {
        wpm = (defaults.object(forKey: Keys.wpm) as? Int) ?? 20
        toneHz = (defaults.object(forKey: Keys.tone) as? Int) ?? 700
        characterSet = defaults.string(forKey: Keys.charset) ?? "letters"
        playModeInterval = defaults.bool(forKey: Keys.interval)
    }
}
