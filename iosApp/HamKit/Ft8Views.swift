import SwiftUI
import Shared

/// FT8 自动应答状态机（简化版，对齐 Android 自动通联的核心路径）：
/// 听到呼叫本台 → 回报告 → 收到报告回 RR73 → 记 QSO。
/// 自动 CQ 呼叫与多候选择优暂未移植。
final class Ft8Sequencer: ObservableObject {

    enum Stage {
        case idle
        case reportSent
        case rr73Sent
        case completed
    }

    @Published private(set) var stage: Stage = .idle
    @Published private(set) var target: String = ""

    /// 处理一条解码消息；返回需要自动发射的文本（nil 表示不动作）。
    func handle(message: String, myCallsign: String) -> String? {
        let my = myCallsign.uppercased()
        guard !my.isEmpty else { return nil }
        let tokens = message.uppercased().split(separator: " ").map(String.init)
        guard tokens.contains(my) else { return nil }

        // 识别对方呼号：排除 CQ / 本台 / 报告 / 修饰词
        let modifiers: Set<String> = ["CQ", "DE", "RR73", "RR", "73", "TU", "R", "K", "BK"]
        let other = tokens.first { token in
            token != my && !modifiers.contains(token) && !token.hasPrefix("+") && !token.hasPrefix("-")
                && token.rangeOfCharacter(from: .decimalDigits) != nil
        } ?? tokens.first { $0 != my && !modifiers.contains($0) }
        guard let other, !other.isEmpty else { return nil }

        switch stage {
        case .idle, .completed:
            target = other
            stage = .reportSent
            return "\(other) \(my) -05"
        case .reportSent:
            if tokens.contains("RR73") || tokens.contains("73") || tokens.contains("RR") {
                stage = .completed
                return "\(other) \(my) 73"
            }
            if tokens.contains(where: { $0.hasPrefix("+") || $0.hasPrefix("-") }) {
                stage = .rr73Sent
                return "\(other) \(my) R-05"
            }
            return nil
        case .rr73Sent:
            if tokens.contains("RR73") || tokens.contains("73") || tokens.contains("RR") {
                stage = .completed
                return nil
            }
            return nil
        }
    }

    func reset() {
        stage = .idle
        target = ""
    }
}

/// FT8 主页面：对应 Android `Ft8Main` —— 设置摘要、消息编辑、发射/接收、解码结果。
struct Ft8MainView: View {

    @ObservedObject var store = Ft8Store.shared
    @StateObject private var audio = Ft8Audio()
    @StateObject private var sequencer = Ft8Sequencer()

    @State private var message = ""
    @State private var autoReply = false
    @State private var autoCq = false
    @State private var cqTimer: Timer?
    @State private var status = "空闲"
    @State private var qsoSeed: Ft8LogEntry?
    /// 解码结果放在会话级 store 里（原先是本页 @State），AI 助手要读同一份数据。
    @ObservedObject private var log = Ft8LogStore.shared

    var body: some View {
        List {
            Section("台站") {
                LabeledContent("呼号", value: store.callsign.isEmpty ? "未设置" : store.callsign)
                LabeledContent("网格", value: store.grid.isEmpty ? "未设置" : store.grid)
                LabeledContent("频段", value: "\(store.band) · \(String(format: "%.3f MHz", Double(store.bandFreqHz) / 1_000_000))")
                NavigationLink(value: Route.ft8Settings) {
                    Label("FT8 设置", systemImage: "gearshape")
                }
            }

            Section("消息") {
                TextField("如 CQ BH4XYZ OM44", text: $message)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                Button("生成 CQ") {
                    message = "CQ \(store.callsign) \(store.grid)".trimmingCharacters(in: .whitespaces)
                }
                .disabled(store.callsign.isEmpty)
                Text(status)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Section("发射 / 接收") {
                Toggle("自动应答（听到呼叫本台时）", isOn: $autoReply)
                Toggle("自动 CQ（每 30 秒主叫）", isOn: $autoCq)
                    .onChange(of: autoCq) { enabled in
                        if enabled { startAutoCq() } else { stopAutoCq() }
                    }
                Button(audio.isPlaying ? "停止发射" : "发射（15 秒）") {
                    if audio.isPlaying {
                        audio.stopPlayback()
                        status = "已停止"
                    } else {
                        transmit(message)
                    }
                }
                .disabled(message.isEmpty)

                Button(audio.isRecording ? "正在接收…" : "接收（录音 15 秒）") {
                    if audio.isRecording {
                        audio.cancelRecording()
                        log.endDecoding()
                        status = "已取消接收"
                    } else {
                        receive()
                    }
                }
                .disabled(audio.isRecording)
            }

            Section("解码结果") {
                if log.decodes.isEmpty {
                    Text("还没有解码结果").foregroundStyle(.secondary)
                }
                ForEach(log.decodes) { item in
                    VStack(alignment: .leading, spacing: 3) {
                        Text(item.message).font(.body.monospaced())
                        Text(String(format: "SNR %d dB · DT %.2f s · %.0f Hz", item.snr, item.dtSeconds, item.freqHz))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                        Button("记入 QSO 日志") {
                            qsoSeed = item
                        }
                        .font(.caption)
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("FT8")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: Binding(
            get: { qsoSeed.map { QsoSeed(message: $0) } },
            set: { if $0 == nil { qsoSeed = nil } }
        )) { seed in
            NavigationStack {
                QsoEditView(entry: makeQso(from: seed.message.message))
            }
        }
        .onDisappear {
            stopAutoCq()
            audio.stopPlayback()
            audio.cancelRecording()
        }
    }

    private func startAutoCq() {
        guard !store.callsign.isEmpty else {
            autoCq = false
            status = "自动 CQ 需要先填呼号"
            return
        }
        stopAutoCq()
        status = "自动 CQ 已开启"
        cqTimer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { _ in
            // 只有在没有进行中的自动通联、且空闲时才发 CQ
            guard sequencer.stage == .idle, !audio.isPlaying, !audio.isRecording else { return }
            let grid = store.grid
            let call = store.callsign
            let text = grid.isEmpty ? "CQ \(call)" : "CQ \(call) \(grid)"
            DispatchQueue.main.async {
                transmit(text)
            }
        }
    }

    private func stopAutoCq() {
        cqTimer?.invalidate()
        cqTimer = nil
        if autoCq { status = "自动 CQ 已关闭" }
    }

    private func transmit(_ text: String) {
        let pcmBytes = Ft8Bridge.shared.encodeToPcm(
            message: text,
            callsign: store.callsign,
            grid: store.grid
        )
        guard pcmBytes.size > 0 else {
            status = "消息无法打包（检查呼号/网格格式）"
            return
        }
        var data = Data(count: Int(pcmBytes.size))
        for i in 0..<Int(pcmBytes.size) {
            data[i] = UInt8(bitPattern: pcmBytes.get(index: Int32(i)))
        }
        status = "正在发射：\(text)"
        audio.play(pcm: data)
    }

    /// 自动应答：等到下一个 15 秒偶数时隙再发射，避免与接收窗口重叠。
    private func scheduleAutoTransmit(_ text: String) {
        let now = Date().timeIntervalSince1970
        let slot = 15.0
        let nextSlot = ceil(now / slot) * slot
        let delay = max(0.5, nextSlot - now)
        status = "自动应答已排队：\(text)"
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
            transmit(text)
        }
    }

    private func receive() {
        status = "正在接收 15 秒…"
        log.beginDecoding()
        audio.record(seconds: 15) { data in
            let bytes = KotlinByteArray(size: Int32(data.count))
            for (index, byte) in data.enumerated() {
                bytes.set(index: Int32(index), value: Int8(bitPattern: byte))
            }
            let result = Ft8Bridge.shared.decodePcm(bytes: bytes)
            DispatchQueue.main.async {
                log.endDecoding()
                if let result {
                    log.record(result)
                    status = "解码成功：\(result.message)"
                    if autoReply,
                       let reply = sequencer.handle(message: result.message, myCallsign: store.callsign) {
                        scheduleAutoTransmit(reply)
                    }
                    if sequencer.stage == .completed, !sequencer.target.isEmpty {
                        // 自动通联完成：记入 QSO 日志（未完成标记，等用户确认）
                        store.recordAutoQso(
                            target: sequencer.target,
                            text: result.message,
                            grid: store.grid
                        )
                        sequencer.reset()
                    }
                } else {
                    status = "本时隙没有解出消息"
                }
            }
        }
    }

    private func makeQso(from text: String) -> QsoEntry {
        // FT8 文本常见形式："CQ CALL GRID" / "CALL1 CALL2 REPORT"
        let parts = text.split(separator: " ").map(String.init)
        let call = parts.first(where: { $0 != "CQ" && $0 != store.callsign }) ?? ""
        return QsoEntry(
            callsign: call,
            grid: parts.last?.count == 4 ? (parts.last ?? "") : "",
            reportSent: "-99",
            reportReceived: "-99",
            bandName: store.band,
            freqHz: store.bandFreqHz,
            mode: "FT8",
            qsoTime: Date(),
            isComplete: false,
            operatorCallsign: store.callsign,
            myGrid: store.grid,
            comment: text
        )
    }
}

private struct QsoSeed: Identifiable {
    let message: Ft8LogEntry
    var id: String { message.message }
}

/// FT8 设置页。
struct Ft8SettingsView: View {

    @ObservedObject var store = Ft8Store.shared

    var body: some View {
        Form {
            Section("台站") {
                TextField("呼号", text: $store.callsign)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                TextField("网格（如 OM44）", text: $store.grid)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
            }
            Section("频段") {
                Picker("频段", selection: $store.band) {
                    ForEach(Ft8Store.bands) { band in
                        Text("\(band.id) · \(String(format: "%.3f MHz", Double(band.freqHz) / 1_000_000))")
                            .tag(band.id)
                    }
                }
            }
            Section {
                Text("iOS 端只做音频收发与编解码；自动通联状态机（CQ/应答/73）尚未移植，当前为手动发消息 + 解码后手动记入 QSO。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("FT8 设置")
        .navigationBarTitleDisplayMode(.inline)
    }
}
