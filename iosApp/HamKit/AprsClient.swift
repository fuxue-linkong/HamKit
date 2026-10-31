import Foundation
import Network
import Combine
import Shared

/// APRS-IS TCP 客户端：对应 Android `AprsConnection`。
///
/// 用 Network.framework 的 `NWConnection` 建原始 TCP（APRS-IS 不是 WebSocket/HTTP）；
/// 只负责收发与行拆分，包解析交给共享内核 `Aprs.parsePacket`。
///
/// 「连接莫名其妙关掉」的三个真实原因，这里都补掉了：
/// 1. **对端关闭 / 网络切换**：以前收到 EOF 或 `.failed` 只改一行状态文字，
///    不会重连；现在按 2s → 5s → 15s → 30s → 60s 退避自动重连，
///    并且把原因写进状态栏（不再"莫名其妙"）。
/// 2. **App 进后台**：iOS 会挂起/回收 socket，回前台时连接其实已经死了；
///    现在回前台会调用 `reconnectIfNeeded()` 主动重建。
/// 3. **半行丢失**：以前每个 TCP 分片单独 `components(separatedBy:)`，
///    跨分片的长报文会被截断丢掉；现在用字节缓冲拼行。
///
/// 手动断开（`disconnect()`）会清掉"希望保持连接"的标记，之后不再自动重连。
final class AprsClient {

    private var connection: NWConnection?
    private let queue = DispatchQueue(label: "click.hamkit.aprs")

    /// 用户是否希望保持连接（只有手动断开才会清掉）
    private var wantsConnection = false
    private var host = ""
    private var port: UInt16 = 0
    /// 连接建立后要发的行：登录行 + 可选的 #filter
    private var handshakeLines: [String] = []

    private var retryAttempt = 0
    private var retryWork: DispatchWorkItem?
    /// 跨分片的行缓冲（字节级，避免 UTF-8 被切坏）
    private var buffer = Data()
    /// 本次连接累计收到的行数：用来区分「服务器一句话没说就关」和「收了一段时间后关」
    private var receivedLines = 0

    /// 重连退避（秒）
    private static let backoff: [TimeInterval] = [2, 5, 15, 30, 60]

    var onLine: ((String) -> Void)?
    var onState: ((String) -> Void)?
    /// 连接可用性变化（true = 已建立，可以收发）
    var onConnectedChange: ((Bool) -> Void)?

    private(set) var isConnected = false

    func connect(host: String, port: UInt16, login: String, filter: String?) {
        self.wantsConnection = true
        self.host = host
        self.port = port
        handshakeLines = [login]
        if let filter, !filter.isEmpty {
            // 与 Android `AprsConnection` 完全一致的写法（`# filter xxx`，井号后有空格）。
            // APRS-IS 的过滤器语法在某些入口站点上对 `#filter`（无空格）也会当成
            // 非法客户端行直接断连，这里不做「优化」，照抄能跑通的实现。
            handshakeLines.append("# filter \(filter)")
        }
        retryAttempt = 0
        // 统一在 client 自己的串行队列上建连，避免和重连定时器并发改 connection
        queue.async { [weak self] in self?.openConnection() }
    }

    /// App 回到前台时调用：socket 可能已经在后台被系统回收。
    func reconnectIfNeeded() {
        guard wantsConnection, !isConnected else { return }
        retryWork?.cancel()
        retryWork = nil
        retryAttempt = 0
        queue.async { [weak self] in self?.openConnection() }
    }

    /// 强制重建连接（后台挂起后 socket 可能变成「僵尸」：状态仍 ready，但收不到数据）。
    func forceReconnect() {
        guard wantsConnection else { return }
        retryWork?.cancel()
        retryWork = nil
        retryAttempt = 0
        queue.async { [weak self] in self?.openConnection() }
    }

    func send(_ line: String) {
        guard let connection else { return }
        connection.send(
            content: Data((line + "\r\n").utf8),
            completion: .contentProcessed { [weak self] error in
                // 写失败以前被完全吞掉（.contentProcessed { _ in }），
                // 网络把 14580 拦掉时表现就是「发出去了、然后被断开」。
                if let error {
                    self?.onState?("发送失败：\(error.localizedDescription)")
                }
            }
        )
    }

    func disconnect() {
        wantsConnection = false
        retryWork?.cancel()
        retryWork = nil
        retryAttempt = 0
        queue.async { [weak self] in
            guard let self else { return }
            self.connection?.cancel()
            self.connection = nil
            self.buffer = Data()
            self.setConnected(false)
        }
        onState?("未连接")
    }

    // MARK: - 连接

    private func openConnection() {
        retryWork?.cancel()
        retryWork = nil
        connection?.cancel()
        connection = nil
        buffer = Data()
        receivedLines = 0
        onState?("连接 \(host):\(port)…")

        guard let nwPort = NWEndpoint.Port(rawValue: port) else {
            onState?("端口无效：\(port)")
            return
        }

        let conn = NWConnection(host: NWEndpoint.Host(host), port: nwPort, using: .tcp)
        connection = conn
        conn.stateUpdateHandler = { [weak self] state in
            guard let self, self.connection === conn else { return }
            switch state {
            case .ready:
                self.retryAttempt = 0
                self.setConnected(true)
                self.onState?("已连接 \(self.host):\(self.port)")
                for line in self.handshakeLines { self.send(line) }
                self.receiveLoop(on: conn)
            case .waiting(let error):
                // 网络暂时不可用（切换 Wi-Fi/蜂窝、无信号）：NWConnection 会自己等，
                // 这里只把原因显示出来，不取消连接。
                self.setConnected(false)
                self.onState?("网络不可用，等待恢复：\(error.localizedDescription)")
            case .failed(let error):
                self.failAndRetry(reason: "连接失败：\(error.localizedDescription)")
            case .cancelled:
                self.setConnected(false)
            case .setup, .preparing:
                break
            @unknown default:
                break
            }
        }
        conn.start(queue: queue)
    }

    private func receiveLoop(on connection: NWConnection) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 16 * 1024) {
            [weak self] data, _, isComplete, error in
            guard let self, self.connection === connection else { return }

            if let data, !data.isEmpty {
                self.buffer.append(data)
                // 超过 64KB 还没等到行尾，说明对端数据异常，直接丢最老的字节
                if self.buffer.count > 64 * 1024 {
                    self.buffer.removeFirst(self.buffer.count - 64 * 1024)
                }
                self.drainLines()
            }

            if let error {
                self.failAndRetry(reason: "接收失败：\(error.localizedDescription)")
                return
            }
            if isComplete {
                // 对端关闭（服务器踢线、NAT 超时、切换网络等）。
                // 带上「这次收到过几行」：0 行说明服务器连招呼（# aprsc …）都没发就关了，
                // 基本可以判定是入口站点/网络直接掐断，而不是登录内容有问题。
                self.failAndRetry(
                    reason: "服务器关闭连接（本次收到 \(self.receivedLines) 行）"
                )
                return
            }
            self.receiveLoop(on: connection)
        }
    }

    /// 从缓冲里按 CRLF 切出完整行，剩下的半行留到下次。
    private func drainLines() {
        let crlf = Data("\r\n".utf8)
        while let range = buffer.range(of: crlf) {
            let lineData = buffer.subdata(in: buffer.startIndex..<range.lowerBound)
            buffer.removeSubrange(buffer.startIndex..<range.upperBound)
            if let line = String(data: lineData, encoding: .utf8), !line.isEmpty {
                receivedLines += 1
                onLine?(line)
            }
        }
    }

    private func failAndRetry(reason: String) {
        setConnected(false)
        connection?.cancel()
        connection = nil
        buffer = Data()

        guard wantsConnection else {
            onState?(reason)
            return
        }
        let delay = Self.backoff[min(retryAttempt, Self.backoff.count - 1)]
        retryAttempt += 1
        onState?("\(reason)；\(Int(delay)) 秒后自动重连（第 \(retryAttempt) 次）")
        let work = DispatchWorkItem { [weak self] in self?.openConnection() }
        retryWork = work
        queue.asyncAfter(deadline: .now() + delay, execute: work)
    }

    private func setConnected(_ value: Bool) {
        guard value != isConnected else { return }
        isConnected = value
        onConnectedChange?(value)
    }
}

/// APRS 设置与运行状态（UserDefaults 持久化，对应 Android `AprsConfig`）。
struct AprsConfig: Codable, Equatable {
    var callsign: String = ""
    var ssid: String = ""
    var passcode: String = ""
    var server: String = "china.aprs2.net"
    var port: Int = 14580
    var symbolTable: String = "/"
    var symbolCode: String = ">"
    var comment: String = "HamKit"
    var transmitInterval: Int = 60
    var useCompression: Bool = true
    var enableTransmit: Bool = false
    var enableReceive: Bool = true
    var filter: String = ""
    var licenseConfirmed: Bool = false

    var fullCallsign: String {
        ssid.isEmpty ? callsign : "\(callsign)-\(ssid)"
    }

    var isReadOnly: Bool { passcode.trimmingCharacters(in: .whitespaces).isEmpty }

    var effectivePasscode: String {
        isReadOnly ? "-1" : passcode.trimmingCharacters(in: .whitespaces)
    }
}

/// 收到的 APRS 站点。
struct AprsStation: Identifiable, Codable {
    var id = UUID()
    let callsign: String
    var latitude: Double
    var longitude: Double
    var symbolTable: String
    var symbolCode: String
    var comment: String
    var altitudeMeters: Double
    var course: Int
    var speedKnots: Int
    var lastHeard: Date = Date()
}

/// APRS 消息（收发共用）。
struct AprsChatMessage: Identifiable, Codable {
    var id = UUID()
    let source: String
    let destination: String
    var body: String
    let timestamp: Date
    let isOutgoing: Bool
    var isAcknowledged: Bool = false
}

/// APRS 全局状态：设置、站点、消息、连接状态。
final class AprsStore: ObservableObject {

    static let shared = AprsStore()

    @Published var config: AprsConfig {
        didSet { save() }
    }
    @Published private(set) var stations: [AprsStation] = []
    @Published private(set) var messages: [AprsChatMessage] = []
    @Published private(set) var statusText: String = "未连接"
    @Published private(set) var isConnected = false
    /// 与 APRS-IS 的收发日志（最近 200 行，密码已打码），用来排查「连上就被踢」。
    @Published private(set) var log: [String] = []

    private let client = AprsClient()
    private let defaults = UserDefaults.standard
    private static let key = "hamkit.aprs.config"
    private var beaconTimer: Timer?
    private var currentLatitude: Double = 0
    private var currentLongitude: Double = 0
    /// 进入后台的时间（回前台时据此判断 socket 是否已失效）
    private var backgroundedAt: Date?
    private let dataURL: URL = {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return docs.appendingPathComponent("APRS.json")
    }()

    private init() {
        if let data = defaults.data(forKey: Self.key),
           let decoded = try? JSONDecoder().decode(AprsConfig.self, from: data) {
            config = decoded
        } else {
            config = AprsConfig()
        }

        if let data = try? Data(contentsOf: dataURL),
           let stored = try? JSONDecoder().decode(Persisted.self, from: data) {
            stations = stored.stations
            messages = stored.messages
        }

        client.onState = { [weak self] text in
            DispatchQueue.main.async {
                self?.statusText = text
                self?.appendLog("· \(text)")
            }
        }
        client.onConnectedChange = { [weak self] connected in
            DispatchQueue.main.async {
                self?.isConnected = connected
            }
        }
        client.onLine = { [weak self] line in
            self?.handle(line: line)
        }
    }

    private struct Persisted: Codable {
        var stations: [AprsStation]
        var messages: [AprsChatMessage]
    }

    private func save() {
        if let data = try? JSONEncoder().encode(config) {
            defaults.set(data, forKey: Self.key)
        }
    }

    private func saveData() {
        let payload = Persisted(stations: stations, messages: messages)
        if let data = try? JSONEncoder().encode(payload) {
            try? data.write(to: dataURL, options: .atomic)
        }
    }

    func connect() {
        guard !config.callsign.isEmpty else {
            statusText = "请先填写呼号"
            return
        }
        guard config.licenseConfirmed else {
            statusText = "请先在设置中确认持照与责任声明"
            return
        }
        // APRS-IS 的 `vers` 必须是**两个 token**：软件名 + 版本号。
        // 之前传的是 "HamKit-iOS"（一个 token），服务器直接判
        // `# Invalid login: software name and version are not separated by a space`
        // 并断连 —— 这就是 iOS 一直连不上的根因。
        let appVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0.1.0"
        let softwareVersion = "HamKit \(appVersion)"
        let login = Aprs.shared.formatLogin(
            // APRS-IS 的 login 只接受大写呼号，带空格/小写会被当成非法登录直接断连。
            callsign: config.callsign.trimmingCharacters(in: .whitespaces).uppercased(),
            ssid: config.ssid.trimmingCharacters(in: .whitespaces),
            passcode: config.effectivePasscode,
            version: softwareVersion
        )
        statusText = "正在连接 \(config.server):\(config.port)…"
        // 登录行与 filter 都交给 client 在每次连接建立后发送：
        // 自动重连时 filter 不会丢（以前 filter 只在首次 connect() 时发一次）。
        let filter = effectiveFilter()
        client.connect(
            host: config.server,
            port: UInt16(config.port),
            login: login,
            filter: filter
        )
        appendLog("> user \(config.fullCallsign) pass *** vers \(softwareVersion)")
        appendLog("> # filter \(filter)")
        startBeaconIfNeeded()
    }

    /// 与 Android 一致：用户没填 filter 时给一个**地理范围**过滤器。
    ///
    /// 不设 filter 时，APRS-IS 只推送「发给我这个 callsign」的报文，表现就是
    /// 「连上了，但站点/消息永远是 0」。Android 用固定的 `r/35/105/1000`；
    /// 这里优先用当前观测点，拿不到坐标才退回同一个默认值。
    private func effectiveFilter() -> String {
        let custom = config.filter.trimmingCharacters(in: .whitespaces)
        if !custom.isEmpty { return custom }
        if currentLatitude != 0 || currentLongitude != 0 {
            return String(format: "r/%.3f/%.3f/1000", currentLatitude, currentLongitude)
        }
        return "r/35/105/1000"
    }

    /// 往连接日志里追加一行（时间 + 方向），最多保留 200 行。
    private func appendLog(_ line: String) {
        let stamped = "\(Self.logTime.string(from: Date())) \(line)"
        DispatchQueue.main.async {
            self.log.append(stamped)
            if self.log.count > 200 {
                self.log.removeFirst(self.log.count - 200)
            }
        }
    }

    func clearLog() {
        log.removeAll()
    }

    private static let logTime: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss"
        return f
    }()

    /// App 回到前台：后台期间 socket 很可能已被系统回收，这里主动重建。
    func appDidBecomeActive() {
        // 后台超过 30s：TCP 大概率已是「僵尸连接」（state 还是 ready，但收不到数据），
        // 直接重建比等心跳超时更可靠。
        let longPause = backgroundedAt.map { Date().timeIntervalSince($0) > 30 } ?? false
        backgroundedAt = nil
        if longPause, client.isConnected {
            client.forceReconnect()
            return
        }
        client.reconnectIfNeeded()
    }

    /// App 进入后台：iOS 会挂起 socket，记下时间用于回前台时判断是否要重建。
    func appDidEnterBackground() {
        backgroundedAt = Date()
    }

    func disconnect() {
        beaconTimer?.invalidate()
        beaconTimer = nil
        client.disconnect()
        isConnected = false
        statusText = "未连接"
    }

    func sendMessage(destination: String, body: String) {
        guard isConnected else {
            statusText = "未连接，无法发送"
            return
        }
        let payload = Aprs.shared.formatMessage(destination: destination, message: body, msgNumber: "")
        client.send("\(config.fullCallsign)>APRS,TCPIP*:\(payload)")
        messages.append(
            AprsChatMessage(
                source: config.fullCallsign,
                destination: destination,
                body: body,
                timestamp: Date(),
                isOutgoing: true
            )
        )
        saveData()
    }

    /// 供页面同步当前坐标，Beacon 定时器使用。
    func updatePosition(latitude: Double, longitude: Double) {
        currentLatitude = latitude
        currentLongitude = longitude
    }

    private func startBeaconIfNeeded() {
        beaconTimer?.invalidate()
        beaconTimer = nil
        guard config.enableTransmit, !config.isReadOnly else { return }
        let interval = TimeInterval(max(30, config.transmitInterval))
        beaconTimer = Timer.scheduledTimer(withTimeInterval: interval, repeats: true) { [weak self] _ in
            guard let self, self.isConnected else { return }
            self.sendPosition(latitude: self.currentLatitude, longitude: self.currentLongitude)
        }
    }

    func sendPosition(latitude: Double, longitude: Double) {
        guard isConnected, config.enableTransmit, !config.isReadOnly else { return }
        let payload = Aprs.shared.formatPosition(
            latitude: latitude,
            longitude: longitude,
            symbolTable: config.symbolTable,
            symbolCode: config.symbolCode,
            comment: config.comment,
            compressed: config.useCompression
        )
        client.send("\(config.fullCallsign)>APRS,TCPIP*:\(payload)")
        appendLog("> \(config.fullCallsign)>APRS,TCPIP*:\(payload)")
    }

    /// 服务器注释里带这些词通常意味着登录/过滤器被拒，或者服务器要断开我们。
    private static func isServerErrorLine(_ line: String) -> Bool {
        let lower = line.lowercased()
        let keywords = [
            "invalid", "unverified", "rejected", "denied", "error",
            "already", "duplicate", "closed", "closing", "too many",
            "filter", "login",
        ]
        return keywords.contains { lower.contains($0) }
    }

    private func handle(line: String) {
        appendLog("< \(line)")
        if line.hasPrefix("#") {
            // APRS-IS 服务器注释：登录结果 / 过滤器状态 / 断连原因
            if line.contains("logresp") && line.contains("verified") {
                DispatchQueue.main.async {
                    self.statusText = line.contains("unverified")
                        ? "已连接（只读：未填写 passcode）"
                        : "已连接并验证"
                    self.isConnected = true
                }
            } else if Self.isServerErrorLine(line) {
                // 服务器主动告知的原因（登录被拒 / 重复登录 / 过滤器被拒…）直接显示，
                // 不再只留一句「服务器断开连接」。
                DispatchQueue.main.async {
                    self.statusText = "服务器：\(line)"
                }
            }
            return
        }
        let parsed = Aprs.shared.parsePacket(packet: line)
        guard parsed.type != "unsupported" else { return }

        DispatchQueue.main.async {
            if parsed.type == "position", parsed.hasPosition {
                let station = AprsStation(
                    callsign: parsed.source,
                    latitude: parsed.latitude,
                    longitude: parsed.longitude,
                    symbolTable: parsed.symbolTable,
                    symbolCode: parsed.symbolCode,
                    comment: parsed.comment,
                    altitudeMeters: parsed.altitudeMeters,
                    course: Int(parsed.course),
                    speedKnots: Int(parsed.speedKnots)
                )
                if let index = self.stations.firstIndex(where: { $0.callsign == station.callsign }) {
                    self.stations[index] = station
                } else {
                    self.stations.append(station)
                }
            } else if parsed.type == "message" {
                self.messages.append(
                    AprsChatMessage(
                        source: parsed.source,
                        destination: parsed.destination,
                        body: parsed.body,
                        timestamp: Date(),
                        isOutgoing: false
                    )
                )
            }
            self.saveData()
        }
    }
}
