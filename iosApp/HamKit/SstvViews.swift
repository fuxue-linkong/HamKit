import SwiftUI
import UIKit
import AVFoundation
import PhotosUI
import UniformTypeIdentifiers
import Combine
import Shared

/// SSTV 接收音频：录任意时长，停止后重采样到共享内核要求的采样率。
final class SstvAudio: ObservableObject {

    @Published private(set) var isRecording = false
    @Published private(set) var elapsed: TimeInterval = 0

    private let engine = AVAudioEngine()
    private var samples: [Float] = []
    private var inputRate: Double = 48_000
    private var timer: Timer?
    private var startTime = Date()

    func start() {
        guard !isRecording else { return }
        samples = []
        do {
            try AudioSession.activateRecording()
        } catch {
            return
        }
        let input = engine.inputNode
        let format = input.inputFormat(forBus: 0)
        inputRate = format.sampleRate > 0 ? format.sampleRate : 48_000
        input.removeTap(onBus: 0)
        input.installTap(onBus: 0, bufferSize: 4096, format: format) { [weak self] buffer, _ in
            guard let self, let channel = buffer.floatChannelData?[0] else { return }
            let frames = Int(buffer.frameLength)
            self.samples.append(contentsOf: UnsafeBufferPointer(start: channel, count: frames))
        }
        engine.prepare()
        do {
            try engine.start()
        } catch {
            input.removeTap(onBus: 0)
            return
        }
        isRecording = true
        startTime = Date()
        timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            guard let self else { return }
            self.elapsed = Date().timeIntervalSince(self.startTime)
        }
    }

    /// 不中断录音，取当前缓冲的 PCM 快照（用于边收边预览）。
    func snapshotData() -> Data? {
        guard isRecording, Double(samples.count) > inputRate else { return nil }
        let target = Double(SstvBridge.shared.sampleRate)
        let resampled = Self.resample(samples, from: inputRate, to: target)
        var data = Data(capacity: resampled.count * 2)
        for sample in resampled {
            let clamped = max(-1.0, min(1.0, sample))
            let v = Int16(clamped * 32767.0)
            withUnsafeBytes(of: v.littleEndian) { data.append(contentsOf: $0) }
        }
        return data
    }

    /// 停止并返回目标采样率的 PCM16 数据。
    func stop() -> Data {
        timer?.invalidate()
        timer = nil
        engine.inputNode.removeTap(onBus: 0)
        if engine.isRunning { engine.stop() }
        isRecording = false

        let target = Double(SstvBridge.shared.sampleRate)
        let resampled = Self.resample(samples, from: inputRate, to: target)
        samples = []
        elapsed = 0

        var data = Data(capacity: resampled.count * 2)
        for sample in resampled {
            let clamped = max(-1.0, min(1.0, sample))
            let v = Int16(clamped * 32767.0)
            withUnsafeBytes(of: v.littleEndian) { data.append(contentsOf: $0) }
        }
        return data
    }

    func cancel() {
        timer?.invalidate()
        timer = nil
        engine.inputNode.removeTap(onBus: 0)
        if engine.isRunning { engine.stop() }
        isRecording = false
        samples = []
        elapsed = 0
    }

    private static func resample(_ input: [Float], from: Double, to: Double) -> [Float] {
        guard !input.isEmpty, from > 0, to > 0 else { return [] }
        if abs(from - to) < 1 { return input }
        let ratio = from / to
        let outCount = Int(Double(input.count) / ratio)
        var out = [Float](repeating: 0, count: outCount)
        for i in 0..<outCount {
            let pos = Double(i) * ratio
            let index = Int(pos)
            let frac = Float(pos - Double(index))
            let a = input[min(index, input.count - 1)]
            let b = input[min(index + 1, input.count - 1)]
            out[i] = a + (b - a) * frac
        }
        return out
    }
}

/// SSTV 图库：图片存 Documents/SSTV，JSON 索引元数据。
struct SstvImage: Identifiable, Codable {
    let id: UUID
    let fileName: String
    let modeName: String
    let receivedAt: Date
    let width: Int
    let height: Int
}

/// 解码结果（String 不能作为 Result 的 Failure，故用自定义枚举）。
enum SstvDecodeOutcome {
    case success(SstvImage)
    case failure(String)
}

final class SstvStore: ObservableObject {

    static let shared = SstvStore()

    @Published private(set) var images: [SstvImage] = []
    @Published var forcedVis: Int = 0
    @Published var autoSave: Bool = true
    /// 「为发射选图」的交接点：图库/详情页选中后写入，发射页读取后清空。
    @Published var txSourceImage: UIImage?

    private let dir: URL
    private let indexURL: URL

    private init() {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        dir = docs.appendingPathComponent("SSTV", isDirectory: true)
        indexURL = dir.appendingPathComponent("index.json")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        load()
    }

    func modes() -> [SstvModeInfo] {
        SstvBridge.shared.modes()
    }

    /// 解码音频文件（WAV/MP3/M4A 等，AVAudioFile 支持的格式）。
    /// 自动重采样到内核采样率；返回与实时接收相同的保存结果。
    func decodeFile(url: URL) -> SstvDecodeOutcome {
        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }

        guard let data = Self.pcm16(from: url, targetRate: Double(SstvBridge.shared.sampleRate)) else {
            return .failure("无法读取音频文件")
        }
        return decode(data: data)
    }

    /// 用 AVAudioFile + AVAudioConverter 读成目标采样率、单声道、PCM16。
    private static func pcm16(from url: URL, targetRate: Double) -> Data? {
        guard let file = try? AVAudioFile(forReading: url) else { return nil }
        let sourceFormat = file.processingFormat
        let frameCount = AVAudioFrameCount(file.length)
        guard frameCount > 0,
              let sourceBuffer = AVAudioPCMBuffer(pcmFormat: sourceFormat, frameCapacity: frameCount),
              (try? file.read(into: sourceBuffer)) != nil else {
            return nil
        }

        guard let targetFormat = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: targetRate,
            channels: 1,
            interleaved: false
        ) else { return nil }

        var output: AVAudioPCMBuffer?
        if abs(sourceFormat.sampleRate - targetRate) < 1 && sourceFormat.channelCount == 1 {
            output = sourceBuffer
        } else {
            guard let converter = AVAudioConverter(from: sourceFormat, to: targetFormat) else { return nil }
            let ratio = targetRate / sourceFormat.sampleRate
            let capacity = AVAudioFrameCount(Double(frameCount) * ratio + 1024)
            guard let converted = AVAudioPCMBuffer(pcmFormat: targetFormat, frameCapacity: capacity) else {
                return nil
            }
            var error: NSError?
            var supplied = false
            converter.convert(to: converted, error: &error) { _, status in
                if supplied {
                    status.pointee = .endOfStream
                    return nil
                }
                supplied = true
                status.pointee = .haveData
                return sourceBuffer
            }
            if error != nil { return nil }
            output = converted
        }

        guard let buffer = output,
              let channel = buffer.floatChannelData?[0] else { return nil }
        var data = Data(capacity: Int(buffer.frameLength) * 2)
        for i in 0..<Int(buffer.frameLength) {
            let clamped = max(-1.0, min(1.0, channel[i]))
            let v = Int16(clamped * 32767.0)
            withUnsafeBytes(of: v.littleEndian) { data.append(contentsOf: $0) }
        }
        return data
    }

    /// 边收边预览：解码当前缓冲但**不落盘**。
    func previewImage(data: Data) -> UIImage? {
        let bytes = KotlinByteArray(size: Int32(data.count))
        for (index, byte) in data.enumerated() {
            bytes.set(index: Int32(index), value: Int8(bitPattern: byte))
        }
        let outcome = SstvBridge.shared.decodePcm(bytes: bytes, forcedVis: Int32(forcedVis))
        guard outcome.success else { return nil }
        return Self.makeImage(
            pixels: outcome.pixels,
            width: Int(outcome.width),
            height: Int(outcome.height)
        )
    }

    /// 解码 PCM 数据为图片；失败返回错误文本。
    func decode(data: Data) -> SstvDecodeOutcome {
        let bytes = KotlinByteArray(size: Int32(data.count))
        for (index, byte) in data.enumerated() {
            bytes.set(index: Int32(index), value: Int8(bitPattern: byte))
        }
        let outcome = SstvBridge.shared.decodePcm(bytes: bytes, forcedVis: Int32(forcedVis))
        guard outcome.success else {
            return .failure(outcome.error.isEmpty ? "解码失败" : outcome.error)
        }
        guard let image = Self.makeImage(
            pixels: outcome.pixels,
            width: Int(outcome.width),
            height: Int(outcome.height)
        ) else {
            return .failure("无法生成图像")
        }
        guard autoSave else { return .success(SstvImage(
            id: UUID(), fileName: "", modeName: outcome.modeName,
            receivedAt: Date(), width: Int(outcome.width), height: Int(outcome.height)
        )) }

        let id = UUID()
        let fileName = "\(id.uuidString).png"
        let fileURL = dir.appendingPathComponent(fileName)
        guard let png = image.pngData(), (try? png.write(to: fileURL)) != nil else {
            return .failure("保存图片失败")
        }
        let record = SstvImage(
            id: id,
            fileName: fileName,
            modeName: outcome.modeName,
            receivedAt: Date(),
            width: Int(outcome.width),
            height: Int(outcome.height)
        )
        images.insert(record, at: 0)
        saveIndex()
        return .success(record)
    }

    func image(for record: SstvImage) -> UIImage? {
        UIImage(contentsOfFile: dir.appendingPathComponent(record.fileName).path)
    }

    func fileURL(for record: SstvImage) -> URL {
        dir.appendingPathComponent(record.fileName)
    }

    func delete(_ record: SstvImage) {
        try? FileManager.default.removeItem(at: dir.appendingPathComponent(record.fileName))
        images.removeAll { $0.id == record.id }
        saveIndex()
    }

    private func load() {
        guard let data = try? Data(contentsOf: indexURL),
              let decoded = try? JSONDecoder().decode([SstvImage].self, from: data) else { return }
        images = decoded
    }

    private func saveIndex() {
        guard let data = try? JSONEncoder().encode(images) else { return }
        try? data.write(to: indexURL, options: .atomic)
    }

    private static func makeImage(pixels: KotlinIntArray, width: Int, height: Int) -> UIImage? {
        guard width > 0, height > 0 else { return nil }
        var rgba = [UInt8](repeating: 0, count: width * height * 4)
        for i in 0..<(width * height) {
            let rgb = pixels.get(index: Int32(i))
            rgba[i * 4] = UInt8((rgb >> 16) & 0xFF)
            rgba[i * 4 + 1] = UInt8((rgb >> 8) & 0xFF)
            rgba[i * 4 + 2] = UInt8(rgb & 0xFF)
            rgba[i * 4 + 3] = 255
        }
        guard let provider = CGDataProvider(data: Data(rgba) as CFData),
              let cgImage = CGImage(
                width: width,
                height: height,
                bitsPerComponent: 8,
                bitsPerPixel: 32,
                bytesPerRow: width * 4,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
                provider: provider,
                decode: nil,
                shouldInterpolate: false,
                intent: .defaultIntent
              ) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}

/// SSTV 主页面：选择模式 → 开始/停止接收 → 解码入图库。
struct SstvMainView: View {

    @ObservedObject var store = SstvStore.shared
    @StateObject private var audio = SstvAudio()

    var presetModeName: String?
    var autoStart: Bool

    @State private var status = "空闲"
    @State private var lastImage: UIImage?
    @State private var showAudioImporter = false
    @State private var previewTimer: Timer?

    var body: some View {
        List {
            Section("接收") {
                Picker("模式", selection: $store.forcedVis) {
                    Text("自动识别（VIS）").tag(0)
                    ForEach(store.modes(), id: \.visCode) { mode in
                        Text("\(mode.name) · \(mode.width)×\(mode.height)").tag(Int(mode.visCode))
                    }
                }
                Button(audio.isRecording ? "停止并解码" : "开始接收") {
                    if audio.isRecording {
                        previewTimer?.invalidate()
                        previewTimer = nil
                        let data = audio.stop()
                        status = "解码中…"
                        DispatchQueue.global(qos: .userInitiated).async {
                            let result = store.decode(data: data)
                            DispatchQueue.main.async {
                                switch result {
                                case .success(let image):
                                    status = "解码成功：\(image.modeName) \(image.width)×\(image.height)"
                                    lastImage = store.image(for: image)
                                case .failure(let error):
                                    status = "解码失败：\(error)"
                                }
                            }
                        }
                    } else {
                        audio.start()
                        status = "正在接收…（每 12 秒预览一次）"
                        startPreviewTimer()
                    }
                }
                if audio.isRecording {
                    Text(String(format: "已录 %.1f 秒", audio.elapsed))
                        .font(.footnote.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                Text(status).font(.footnote).foregroundStyle(.secondary)
            }

            if let lastImage {
                Section("最近解码") {
                    Image(uiImage: lastImage)
                        .resizable()
                        .scaledToFit()
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                }
            }

            Section("音频文件") {
                Button {
                    showAudioImporter = true
                } label: {
                    Label("从音频文件解码", systemImage: "doc.badge.plus")
                }
                Text("支持 WAV / M4A / MP3 等；自动重采样到 SSTV 解码采样率。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Section("图库") {
                NavigationLink(value: Route.sstvGallery(pickForTx: false)) {
                    LabeledContent("已保存图片", value: "\(store.images.count)")
                }
                NavigationLink(value: Route.sstvSettings) {
                    Label("SSTV 设置", systemImage: "gearshape")
                }
            }

            Section {
                NavigationLink(value: Route.sstvTx) {
                    Label("发射", systemImage: "waveform")
                }
            } footer: {
                Text("发射：选图（相册或 SSTV 图库）→ 编码成 SSTV 音频 → 播放，或导出 WAV 用别的设备播。")
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("SSTV")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            if let presetModeName,
               let match = store.modes().first(where: { $0.name == presetModeName }) {
                store.forcedVis = Int(match.visCode)
            }
            if autoStart && !audio.isRecording {
                audio.start()
                status = "正在接收…（每 12 秒预览一次）"
                startPreviewTimer()
            }
        }
        .onDisappear {
            previewTimer?.invalidate()
            previewTimer = nil
            audio.cancel()
        }
        .fileImporter(
            isPresented: $showAudioImporter,
            allowedContentTypes: [.audio],
            allowsMultipleSelection: false
        ) { result in
            guard case .success(let urls) = result, let url = urls.first else { return }
            status = "正在读取音频文件…"
            DispatchQueue.global(qos: .userInitiated).async {
                let decoded = store.decodeFile(url: url)
                DispatchQueue.main.async {
                    switch decoded {
                    case .success(let image):
                        status = "解码成功：\(image.modeName) \(image.width)×\(image.height)"
                        lastImage = store.image(for: image)
                    case .failure(let error):
                        status = "解码失败：\(error)"
                    }
                }
            }
        }
    }

    /// 边收边预览：每 12 秒对当前录音缓冲尝试解码一帧，不落盘。
    private func startPreviewTimer() {
        previewTimer?.invalidate()
        previewTimer = Timer.scheduledTimer(withTimeInterval: 12, repeats: true) { _ in
            guard let data = audio.snapshotData() else { return }
            DispatchQueue.global(qos: .utility).async {
                let image = store.previewImage(data: data)
                DispatchQueue.main.async {
                    if let image {
                        lastImage = image
                        status = "接收中，已预览当前进度"
                    }
                }
            }
        }
    }
}

/// SSTV 图库页。
struct SstvGalleryView: View {

    @ObservedObject var store = SstvStore.shared
    var pickForTx: Bool
    @Environment(\.dismiss) private var dismiss

    private let columns = [GridItem(.adaptive(minimum: 140), spacing: 8)]

    var body: some View {
        Group {
            if store.images.isEmpty {
                Text("暂无 SSTV 图片").foregroundStyle(.secondary)
            } else {
                ScrollView {
                    if pickForTx {
                        Text("点击任意图片，把它带回「SSTV 发射」页作为发射内容。")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal)
                            .padding(.top, 8)
                    }
                    LazyVGrid(columns: columns, spacing: 8) {
                        ForEach(store.images) { record in
                            if pickForTx {
                                Button {
                                    store.txSourceImage = store.image(for: record)
                                    dismiss()
                                } label: {
                                    cell(record)
                                }
                                .buttonStyle(.plain)
                            } else {
                                NavigationLink {
                                    SstvImageDetailView(record: record)
                                } label: {
                                    cell(record)
                                }
                            }
                        }
                    }
                    .padding()
                }
            }
        }
        .navigationTitle(pickForTx ? "选择发射图片" : "SSTV 图库")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func cell(_ record: SstvImage) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            if let image = store.image(for: record) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .frame(height: 110)
                    .clipped()
                    .clipShape(RoundedRectangle(cornerRadius: 8))
            }
            Text(record.modeName).font(.caption)
            Text(record.receivedAt, style: .date)
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
    }
}

struct SstvImageDetailView: View {
    @ObservedObject var store = SstvStore.shared
    let record: SstvImage
    @Environment(\.dismiss) private var dismiss
    @State private var shareURL: URL?
    @State private var showTx = false

    var body: some View {
        Group {
            if let image = store.image(for: record) {
                ScrollView {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                }
            } else {
                Text("图片已丢失").foregroundStyle(.secondary)
            }
        }
        .navigationTitle(record.modeName)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                // 把这张图直接带进发射页（复用同一份解码结果，省得再从相册找）
                Button {
                    store.txSourceImage = store.image(for: record)
                    showTx = true
                } label: {
                    Image(systemName: "waveform")
                }
                .disabled(store.image(for: record) == nil)
                Button {
                    shareURL = store.fileURL(for: record)
                } label: {
                    Image(systemName: "square.and.arrow.up")
                }
                .disabled(store.image(for: record) == nil)
                Button(role: .destructive) {
                    store.delete(record)
                    dismiss()
                } label: {
                    Image(systemName: "trash")
                }
            }
        }
        .navigationDestination(isPresented: $showTx) {
            SstvTxView()
        }
        .sheet(item: Binding(
            get: { shareURL.map { SharedSstvFile(url: $0) } },
            set: { if $0 == nil { shareURL = nil } }
        )) { file in
            ShareSheet(url: file.url)
        }
    }
}

private struct SharedSstvFile: Identifiable {
    let url: URL
    var id: String { url.absoluteString }
}

/// SSTV 设置页。
struct SstvSettingsView: View {

    @ObservedObject var store = SstvStore.shared

    var body: some View {
        Form {
            Section("接收") {
                Toggle("解码后自动保存到图库", isOn: $store.autoSave)
            }
            Section {
                Text("iOS 端只支持前台接收；锁屏/后台持续的音频采集受系统限制，无法保证像 Android 前台服务那样连续工作。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("SSTV 设置")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// SSTV 发射页：相册/图库选图 → 编码为 SSTV 音频 → 播放，或导出 WAV 交给别的设备播。
struct SstvTxView: View {

    @ObservedObject var store = SstvStore.shared
    @StateObject private var player = SstvPlayer()

    @State private var pickerItem: PhotosPickerItem?
    @State private var image: UIImage?
    @State private var modeVis: Int = 0
    @State private var status = "选择一张图片开始"
    @State private var isEncoding = false
    @State private var progress: Double = 0
    @State private var progressTimer: Timer?
    /// 最近一次编码结果：导出 WAV / 重播都复用它，避免重复编码
    @State private var lastPcm: Data?
    @State private var lastMode: SstvModeInfo?
    @State private var shareURL: URL?

    private var modes: [SstvModeInfo] {
        store.modes().filter { $0.visCode != 0 }
    }

    private var selectedMode: SstvModeInfo? {
        modes.first { Int($0.visCode) == modeVis } ?? modes.first
    }

    var body: some View {
        Form {
            Section("图片") {
                PhotosPicker(selection: $pickerItem, matching: .images) {
                    Label("从相册选择", systemImage: "photo.on.rectangle")
                }
                NavigationLink(value: Route.sstvGallery(pickForTx: true)) {
                    LabeledContent("从 SSTV 图库选择", value: "\(store.images.count) 张")
                }
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                        .frame(maxHeight: 180)
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                }
            }

            Section("模式") {
                Picker("SSTV 模式", selection: $modeVis) {
                    ForEach(modes, id: \.visCode) { mode in
                        Text("\(mode.name) · \(mode.width)×\(mode.height) · \(Int(mode.frameSeconds))s")
                            .tag(Int(mode.visCode))
                    }
                }
            }

            Section {
                Button(isEncoding ? "编码中…" : (player.isPlaying ? "停止发射" : "开始发射")) {
                    if player.isPlaying {
                        player.stop()
                        stopProgress()
                        status = "已停止"
                    } else {
                        encodeAndPlay()
                    }
                }
                .disabled(isEncoding || image == nil)

                if player.isPlaying {
                    ProgressView(value: progress)
                    Text(String(format: "已发送 %.0f%%", progress * 100))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                Text(status).font(.footnote).foregroundStyle(.secondary)
            } footer: {
                Text("发射 = 把图片编码成 SSTV 音频后播放：用音频线（最好带衰减）接电台 MIC 口，手机音量调到最大，发射期间保持 App 在前台。")
            }

            Section("导出") {
                Button {
                    exportWav()
                } label: {
                    Label("导出 WAV", systemImage: "square.and.arrow.up")
                }
                .disabled(lastPcm == nil)
                if let lastMode {
                    Text("当前缓存：\(lastMode.name) · \(Int(lastMode.frameSeconds)) 秒")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle("SSTV 发射")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            if modeVis == 0 { modeVis = Int(modes.first?.visCode ?? 0) }
            // 图库「为发射选图」回来时接住图片
            if let picked = store.txSourceImage {
                image = picked
                store.txSourceImage = nil
                lastPcm = nil
                lastMode = nil
                status = "已从图库选入，确认模式后开始发射"
            }
        }
        .onDisappear {
            stopProgress()
            player.stop()
        }
        .onChange(of: modeVis) { _ in
            // 换模式后旧音频不能再用（帧长不同），避免导出张冠李戴
            lastPcm = nil
            lastMode = nil
        }
        .onChange(of: pickerItem) { item in
            guard let item else { return }
            Task {
                if let data = try? await item.loadTransferable(type: Data.self),
                   let loaded = UIImage(data: data) {
                    await MainActor.run {
                        image = loaded
                        lastPcm = nil
                        lastMode = nil
                        status = "图片已就绪，确认模式后开始发射"
                    }
                }
            }
        }
        .sheet(item: Binding(
            get: { shareURL.map { SharedSstvFile(url: $0) } },
            set: { if $0 == nil { shareURL = nil } }
        )) { file in
            ShareSheet(url: file.url)
        }
    }

    private func encodeAndPlay() {
        guard let image, let mode = selectedMode else { return }
        isEncoding = true
        status = "正在编码…"
        let targetWidth = Int(mode.width)
        let targetHeight = Int(mode.height)
        let vis = Int(mode.visCode)

        DispatchQueue.global(qos: .userInitiated).async {
            let pixels = Self.rgbPixels(from: image, width: targetWidth, height: targetHeight)
            guard !pixels.isEmpty else {
                DispatchQueue.main.async {
                    isEncoding = false
                    status = "图片转换失败"
                }
                return
            }
            let kotlinPixels = KotlinIntArray(size: Int32(pixels.count))
            for (index, value) in pixels.enumerated() {
                kotlinPixels.set(index: Int32(index), value: Int32(bitPattern: value))
            }
            let pcm = SstvBridge.shared.encodePcm(
                pixels: kotlinPixels,
                width: Int32(targetWidth),
                height: Int32(targetHeight),
                forcedVis: Int32(vis),
                freqOffsetHz: 0
            )
            guard pcm.size > 0 else {
                DispatchQueue.main.async {
                    isEncoding = false
                    status = "该模式暂不支持编码"
                }
                return
            }
            var data = Data(count: Int(pcm.size))
            for i in 0..<Int(pcm.size) {
                data[i] = UInt8(bitPattern: pcm.get(index: Int32(i)))
            }
            DispatchQueue.main.async {
                isEncoding = false
                lastPcm = data
                lastMode = mode
                status = "正在发射 \(mode.name)…"
                startProgress(duration: mode.frameSeconds)
                player.play(pcm: data, sampleRate: Int(SstvBridge.shared.sampleRate)) {
                    stopProgress()
                    progress = 1
                    status = "发射完成"
                }
            }
        }
    }

    // MARK: - 发射进度 / 导出

    private func startProgress(duration: TimeInterval) {
        progress = 0
        progressTimer?.invalidate()
        guard duration > 0 else { return }
        progressTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { _ in
            progress = min(1, progress + 0.5 / duration)
        }
    }

    private func stopProgress() {
        progressTimer?.invalidate()
        progressTimer = nil
    }

    /// 把最近一次编码结果写成 16-bit 单声道 WAV，交给系统分享。
    private func exportWav() {
        guard let pcm = lastPcm, let mode = lastMode else { return }
        let name = "HamKit-SSTV-\(mode.name)-\(Int(Date().timeIntervalSince1970)).wav"
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        let wav = Self.wavData(pcm: pcm, sampleRate: Int(SstvBridge.shared.sampleRate))
        do {
            try wav.write(to: url, options: .atomic)
            shareURL = url
            status = "已导出 \(name)"
        } catch {
            status = "导出失败：\(error.localizedDescription)"
        }
    }

    /// 最小可用的 WAV 封装（PCM16 单声道）。
    private static func wavData(pcm: Data, sampleRate: Int) -> Data {
        var data = Data()
        func appendUInt32(_ value: UInt32) {
            var v = value.littleEndian
            withUnsafeBytes(of: &v) { data.append(contentsOf: $0) }
        }
        func appendUInt16(_ value: UInt16) {
            var v = value.littleEndian
            withUnsafeBytes(of: &v) { data.append(contentsOf: $0) }
        }
        let channels: UInt16 = 1
        let bits: UInt16 = 16
        let byteRate = UInt32(sampleRate * Int(channels) * Int(bits) / 8)
        let blockAlign = channels * bits / 8

        data.append(contentsOf: Array("RIFF".utf8))
        appendUInt32(UInt32(36 + pcm.count))
        data.append(contentsOf: Array("WAVE".utf8))
        data.append(contentsOf: Array("fmt ".utf8))
        appendUInt32(16)                 // fmt chunk 长度
        appendUInt16(1)                  // PCM
        appendUInt16(channels)
        appendUInt32(UInt32(sampleRate))
        appendUInt32(byteRate)
        appendUInt16(blockAlign)
        appendUInt16(bits)
        data.append(contentsOf: Array("data".utf8))
        appendUInt32(UInt32(pcm.count))
        data.append(pcm)
        return data
    }

    /// UIImage → 指定尺寸的 0xRRGGBB 行优先数组。
    private static func rgbPixels(from image: UIImage, width: Int, height: Int) -> [UInt32] {
        guard width > 0, height > 0 else { return [] }
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: width, height: height))
        let resized = renderer.image { _ in
            image.draw(in: CGRect(x: 0, y: 0, width: width, height: height))
        }
        guard let cgImage = resized.cgImage else { return [] }
        var rgba = [UInt8](repeating: 0, count: width * height * 4)
        guard let context = CGContext(
            data: &rgba,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: width * 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return [] }
        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))

        var pixels = [UInt32](repeating: 0, count: width * height)
        for y in 0..<height {
            for x in 0..<width {
                let i = (y * width + x) * 4
                let r = UInt32(rgba[i])
                let g = UInt32(rgba[i + 1])
                let b = UInt32(rgba[i + 2])
                pixels[y * width + x] = (r << 16) | (g << 8) | b
            }
        }
        return pixels
    }
}

/// SSTV 音频播放器：按内核采样率播放编码后的 PCM。
final class SstvPlayer: ObservableObject {

    @Published private(set) var isPlaying = false

    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private var completion: (() -> Void)?

    func play(pcm: Data, sampleRate: Int, onComplete: (() -> Void)? = nil) {
        stop()
        guard !pcm.isEmpty, let format = AVAudioFormat(standardFormatWithSampleRate: Double(sampleRate), channels: 1) else {
            return
        }
        let sampleCount = pcm.count / 2
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(sampleCount)),
              let channel = buffer.floatChannelData?[0] else { return }
        buffer.frameLength = AVAudioFrameCount(sampleCount)
        pcm.withUnsafeBytes { raw in
            let int16 = raw.bindMemory(to: Int16.self)
            for i in 0..<sampleCount {
                channel[i] = Float(Int16(littleEndian: int16[i])) / 32768.0
            }
        }
        if engine.attachedNodes.contains(player) == false {
            engine.attach(player)
        }
        engine.connect(player, to: engine.mainMixerNode, format: format)
        do {
            try AudioSession.activatePlayback()
            try engine.start()
        } catch {
            return
        }
        completion = onComplete
        isPlaying = true
        player.scheduleBuffer(buffer, at: nil, options: []) { [weak self] in
            DispatchQueue.main.async {
                self?.isPlaying = false
                let callback = self?.completion
                self?.completion = nil
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
