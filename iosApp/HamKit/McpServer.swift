import Foundation
import Network
import Shared

// MARK: - 设置存储

/// 局域网 MCP 的开关 / 端口 / Token 持久化（对齐 Android `McpSettingsStore`）。
///
/// Token 需要展示给用户复制，故存 UserDefaults 明文（与 Android 一致）。
final class McpSettingsStore {

    static let shared = McpSettingsStore()

    private let defaults: UserDefaults
    private static let keyEnabled = "hamkit.mcp.enabled"
    private static let keyPort = "hamkit.mcp.port"
    private static let keyToken = "hamkit.mcp.token"
    private static let defaultPort = 8765
    private static let hex = "0123456789abcdef"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    var enabled: Bool {
        get { defaults.bool(forKey: Self.keyEnabled) }
        set { defaults.set(newValue, forKey: Self.keyEnabled) }
    }

    var port: Int {
        get {
            let value = defaults.integer(forKey: Self.keyPort)
            return value == 0 ? Self.defaultPort : min(max(value, 1024), 65535)
        }
        set { defaults.set(min(max(newValue, 1024), 65535), forKey: Self.keyPort) }
    }

    /// 已有 token 直接返回，否则生成并保存。
    func ensureToken() -> String {
        if let existing = defaults.string(forKey: Self.keyToken), !existing.isEmpty {
            return existing
        }
        return resetToken()
    }

    func resetToken() -> String {
        let token = "mcp_" + Self.randomHex(24)
        defaults.set(token, forKey: Self.keyToken)
        return token
    }

    private static func randomHex(_ count: Int) -> String {
        String((0..<count).map { _ in hex.randomElement() ?? "0" })
    }
}

// MARK: - 协议层

/// MCP 协议里与网络无关的纯逻辑：鉴权、只读工具过滤、JSON-RPC 响应构造。
///
/// 对齐 Android `McpProtocol`；工具规格来自共享内核（`AiToolSpec.risk`）。
enum McpProtocol {

    static let protocolVersion = "2024-11-05"

    /// 只读工具才允许通过局域网暴露。
    static func readOnlySpecs(_ specs: [AiToolSpec]) -> [AiToolSpec] {
        specs.filter { $0.risk.name == "READ_ONLY" }
    }

    /// Bearer Token 校验；token 为空时一律拒绝。
    static func authorize(_ authorization: String?, token: String) -> Bool {
        if token.isEmpty { return false }
        return (authorization ?? "").trimmingCharacters(in: .whitespaces)
            .caseInsensitiveCompare("Bearer \(token)") == .orderedSame
    }

    static func toolDescriptors(_ specs: [AiToolSpec]) -> [[String: Any]] {
        readOnlySpecs(specs).map { spec in
            var descriptor: [String: Any] = [
                "name": spec.name,
                "description": spec.description,
            ]
            if let data = spec.parametersJson.data(using: .utf8),
               let schema = try? JSONSerialization.jsonObject(with: data) {
                descriptor["inputSchema"] = schema
            }
            return descriptor
        }
    }

    static func result(id: Any?, _ result: [String: Any]) -> Data {
        var payload: [String: Any] = ["jsonrpc": "2.0", "result": result]
        payload["id"] = id ?? NSNull()
        return encode(payload)
    }

    static func error(id: Any?, code: Int, message: String) -> Data {
        let payload: [String: Any] = [
            "jsonrpc": "2.0",
            "id": id ?? NSNull(),
            "error": ["code": code, "message": message],
        ]
        return encode(payload)
    }

    private static func encode(_ value: [String: Any]) -> Data {
        let options: JSONSerialization.WritingOptions = [.sortedKeys]
        var output = (try? JSONSerialization.data(withJSONObject: value, options: options)) ?? Data("{}".utf8)
        output.append(0x0A)
        return output
    }
}

// MARK: - HTTP 服务器

/// 局域网 MCP 服务（JSON-RPC 2.0 over HTTP，对齐 Android `McpServer`）。
///
/// 不引第三方库：`NWListener` + 手写最小 HTTP/1.1 解析，单端点 `POST /mcp`，
/// 另有 `GET /health`。只暴露内核只读工具；写工具不开放，避免无 UI 确认。
final class McpServer {

    private let port: UInt16
    private let token: String
    private let toolbox: AiToolbox
    private var listener: NWListener?
    private let queue = DispatchQueue(label: "hamkit.mcp.server")
    /// tools/call 的自增序号；所有连接都在同一个串行队列上处理，无需额外加锁。
    private var callSeq = 1

    private(set) var isRunning = false

    init(port: Int, token: String, toolbox: AiToolbox) {
        self.port = UInt16(clamping: port)
        self.token = token
        self.toolbox = toolbox
    }

    /// 返回 false 表示端口被占用/绑定失败。
    func start() -> Bool {
        if isRunning { return true }
        let parameters = NWParameters.tcp
        parameters.allowLocalEndpointReuse = true
        guard let endpointPort = NWEndpoint.Port(rawValue: port),
              let listener = try? NWListener(using: parameters, on: endpointPort) else {
            return false
        }
        self.listener = listener
        listener.newConnectionHandler = { [weak self] connection in
            self?.accept(connection)
        }
        let started = DispatchSemaphore(value: 0)
        var success = false
        listener.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                success = true
                self?.isRunning = true
                started.signal()
            case .failed, .cancelled:
                if !success {
                    started.signal()
                }
                self?.isRunning = false
            default:
                break
            }
        }
        listener.start(queue: queue)
        _ = started.wait(timeout: .now() + 3)
        return success
    }

    func stop() {
        listener?.cancel()
        listener = nil
        isRunning = false
    }

    private func accept(_ connection: NWConnection) {
        connection.start(queue: queue)
        receiveRequest(connection: connection, buffer: Data())
    }

    // MARK: HTTP/1.1 解析

    private func receiveRequest(connection: NWConnection, buffer: Data) {
        // 头部没收齐前按 4KB 逐块收；头收齐后按 content-length 补 body
        if let headerEnd = buffer.range(of: Data("\r\n\r\n".utf8)) {
            let headerData = buffer.subdata(in: buffer.startIndex..<headerEnd.lowerBound)
            guard let headerText = String(data: headerData, encoding: .utf8) else {
                send(connection, status: "400 Bad Request", payload: Data("{\"error\":\"bad request\"}".utf8))
                return
            }
            let lines = headerText.components(separatedBy: "\r\n")
            guard let requestLine = lines.first else {
                send(connection, status: "400 Bad Request", payload: Data("{\"error\":\"bad request\"}".utf8))
                return
            }
            var headers: [String: String] = [:]
            for line in lines.dropFirst() {
                guard let index = line.firstIndex(of: ":") else { continue }
                let name = line[..<index].trimmingCharacters(in: .whitespaces).lowercased()
                let value = line[line.index(after: index)...].trimmingCharacters(in: .whitespaces)
                headers[name] = value
            }
            // 头部上限：与 Android 一致（8KB），防止恶意超长头
            if headerText.count > 8 * 1024 {
                send(connection, status: "431 Request Header Fields Too Large", payload: Data("{\"error\":\"headers too large\"}".utf8))
                return
            }

            let parts = requestLine.split(separator: " ")
            guard parts.count >= 2 else {
                send(connection, status: "400 Bad Request", payload: Data("{\"error\":\"bad request\"}".utf8))
                return
            }
            let method = parts[0].uppercased()
            let path = parts[1].split(separator: "?").first.map(String.init) ?? ""

            let contentLength = Int(headers["content-length"] ?? "0") ?? 0
            if contentLength < 0 || contentLength > 1_048_576 {
                send(connection, status: "413 Payload Too Large", payload: Data("{\"error\":\"body too large\"}".utf8))
                return
            }
            let bodyStart = headerEnd.upperBound
            let available = buffer.distance(from: bodyStart, to: buffer.endIndex)
            if available >= contentLength {
                let bodyData = contentLength > 0
                    ? buffer.subdata(in: bodyStart..<buffer.index(bodyStart, offsetBy: contentLength))
                    : Data()
                handleComplete(connection: connection, method: method, path: path, headers: headers, body: bodyData)
            } else {
                receiveBody(connection: connection, buffer: buffer, headerEnd: headerEnd, contentLength: contentLength)
            }
            return
        }

        // 头部未收齐：继续收
        if buffer.count > 16 * 1024 {
            send(connection, status: "431 Request Header Fields Too Large", payload: Data("{\"error\":\"headers too large\"}".utf8))
            return
        }
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { [weak self] data, _, isComplete, _ in
            guard let self else { return }
            var next = buffer
            if let data { next.append(data) }
            if isComplete {
                self.send(connection, status: "400 Bad Request", payload: Data("{\"error\":\"incomplete request\"}".utf8))
                return
            }
            self.receiveRequest(connection: connection, buffer: next)
        }
    }

    private func receiveBody(connection: NWConnection, buffer: Data, headerEnd: Range<Data.Index>, contentLength: Int) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { [weak self] data, _, isComplete, _ in
            guard let self else { return }
            var next = buffer
            if let data { next.append(data) }
            let bodyStart = headerEnd.upperBound
            let available = next.distance(from: bodyStart, to: next.endIndex)
            if available >= contentLength {
                let headerData = next.subdata(in: next.startIndex..<headerEnd.lowerBound)
                let headerText = String(data: headerData, encoding: .utf8) ?? ""
                let lines = headerText.components(separatedBy: "\r\n")
                let requestLine = lines.first ?? ""
                var headers: [String: String] = [:]
                for line in lines.dropFirst() {
                    guard let index = line.firstIndex(of: ":") else { continue }
                    let name = line[..<index].trimmingCharacters(in: .whitespaces).lowercased()
                    let value = line[line.index(after: index)...].trimmingCharacters(in: .whitespaces)
                    headers[name] = value
                }
                let parts = requestLine.split(separator: " ")
                let method = parts.first.map { $0.uppercased() } ?? ""
                let path = parts.count > 1 ? (parts[1].split(separator: "?").first.map(String.init) ?? "") : ""
                let bodyData = contentLength > 0
                    ? next.subdata(in: bodyStart..<next.index(bodyStart, offsetBy: contentLength))
                    : Data()
                self.handleComplete(connection: connection, method: method, path: path, headers: headers, body: bodyData)
            } else if isComplete {
                self.send(connection, status: "400 Bad Request", payload: Data("{\"error\":\"incomplete body\"}".utf8))
            } else {
                self.receiveBody(connection: connection, buffer: next, headerEnd: headerEnd, contentLength: contentLength)
            }
        }
    }

    private func handleComplete(
        connection: NWConnection,
        method: String,
        path: String,
        headers: [String: String],
        body: Data
    ) {
        let response: (String, Data)
        if method == "GET" && path == "/health" {
            response = ("200 OK", Data("{\"ok\":true}".utf8))
        } else if method == "POST" && path == "/mcp" {
            if !McpProtocol.authorize(headers["authorization"], token: token) {
                response = ("401 Unauthorized", Data("{\"error\":\"invalid token\"}".utf8))
            } else {
                response = ("200 OK", handleRpc(body))
            }
        } else {
            response = ("404 Not Found", Data("{\"error\":\"not found\"}".utf8))
        }
        send(connection, status: response.0, payload: response.1)
    }

    private func send(_ connection: NWConnection, status: String, payload: Data) {
        var header = "HTTP/1.1 \(status)\r\n"
        header += "Content-Type: application/json; charset=utf-8\r\n"
        header += "Content-Length: \(payload.count)\r\n"
        header += "Connection: close\r\n\r\n"
        var output = Data(header.utf8)
        output.append(payload)
        connection.send(content: output, completion: .contentProcessed { [weak connection] _ in
            connection?.cancel()
        })
    }

    // MARK: JSON-RPC

    private func handleRpc(_ body: Data) -> Data {
        guard let request = try? JSONSerialization.jsonObject(with: body) as? [String: Any] else {
            return McpProtocol.error(id: nil, code: -32700, message: "Parse error")
        }
        let id = request["id"]
        let method = request["method"] as? String ?? ""
        let params = request["params"] as? [String: Any] ?? [:]

        switch method {
        case "initialize":
            let requested = params["protocolVersion"] as? String ?? McpProtocol.protocolVersion
            return McpProtocol.result(id: id, [
                "protocolVersion": requested,
                "capabilities": ["tools": [:] as [String: Any]],
                "serverInfo": ["name": "hamkit", "version": "1.0"],
            ])

        case "ping":
            return McpProtocol.result(id: id, [:])

        case "tools/list":
            return McpProtocol.result(id: id, [
                "tools": McpProtocol.toolDescriptors(toolbox.specs()),
            ])

        case "tools/call":
            let name = params["name"] as? String ?? ""
            if name.isEmpty {
                return McpProtocol.error(id: id, code: -32602, message: "Missing tool name")
            }
            // 与 Android 文档口径一致：只放行只读工具，写工具不开放（无 UI 确认）
            let readOnly = McpProtocol.readOnlySpecs(toolbox.specs())
            guard readOnly.contains(where: { $0.name == name }) else {
                return McpProtocol.error(id: id, code: -32602, message: "Tool is not read-only: \(name)")
            }
            let arguments = params["arguments"] as? [String: Any] ?? [:]
            let argumentsData = (try? JSONSerialization.data(withJSONObject: arguments, options: [.sortedKeys])) ?? Data("{}".utf8)
            let argsJson = String(data: argumentsData, encoding: .utf8) ?? "{}"
            let callId = "mcp_\(nextCallSeq())"
            let call = AiToolCall(id: callId, name: name, argsJson: argsJson)

            // AiToolbox.execute 的回调是同步触发的；但工具内部可能读主线程状态，
            // 这里在主队列执行并用信号量等待，保证与 App 数据访问的线程约定一致。
            let semaphore = DispatchSemaphore(value: 0)
            var outcome: AiToolResult?
            DispatchQueue.main.async {
                self.toolbox.execute(call: call) { result in
                    outcome = result
                    semaphore.signal()
                }
            }
            let waitResult = semaphore.wait(timeout: .now() + 30)
            guard waitResult == .success, let result = outcome else {
                return McpProtocol.error(id: id, code: -32000, message: "Tool execution timed out")
            }
            let content: [[String: Any]] = [[
                "type": "text",
                "text": result.modelText,
            ]]
            return McpProtocol.result(id: id, [
                "content": content,
                "isError": result.isError,
            ])

        default:
            return McpProtocol.error(id: id, code: -32601, message: "Method not found: \(method)")
        }
    }

    private func nextCallSeq() -> Int {
        callSeq += 1
        return callSeq
    }
}

// MARK: - 生命周期

/// 局域网 MCP 服务生命周期（对齐 Android `McpController`）。
///
/// iOS 无常驻后台服务：App 在前台时才可用，进入后台由系统挂起
/// （设置页展示的地址只在 App 运行期间有效）。
final class McpController {

    static let shared = McpController()

    private var server: McpServer?
    private let toolbox = AiToolbox(facade: AiAppFacadeImpl.shared)

    private init() {}

    var isRunning: Bool { server?.isRunning == true }

    func maybeStart() -> Bool {
        let store = McpSettingsStore.shared
        guard store.enabled else {
            stop()
            return false
        }
        if let server, server.isRunning { return true }
        server?.stop()
        let instance = McpServer(port: store.port, token: store.ensureToken(), toolbox: toolbox)
        let started = instance.start()
        server = started ? instance : nil
        return started
    }

    func stop() {
        server?.stop()
        server = nil
    }

    @discardableResult
    func setEnabled(_ enabled: Bool) -> Bool {
        McpSettingsStore.shared.enabled = enabled
        if enabled {
            return maybeStart()
        } else {
            stop()
            return true
        }
    }

    @discardableResult
    func setPort(_ port: Int) -> Bool {
        McpSettingsStore.shared.port = port
        if McpSettingsStore.shared.enabled {
            stop()
            return maybeStart()
        }
        return true
    }

    func resetToken() -> String {
        let token = McpSettingsStore.shared.resetToken()
        if McpSettingsStore.shared.enabled {
            stop()
            maybeStart()
        }
        return token
    }

    /// Wi-Fi 的 IPv4 地址（展示给用户拼地址用）；取不到返回 nil。
    func wifiIPv4Address() -> String? {
        var address: String?
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return nil }
        defer { freeifaddrs(ifaddr) }

        var ptr: UnsafeMutablePointer<ifaddrs>? = first
        while let current = ptr {
            let interface = current.pointee
            if let sa = interface.ifa_addr, sa.pointee.sa_family == UInt8(AF_INET) {
                var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                if getnameinfo(
                    sa,
                    socklen_t(sa.pointee.sa_len),
                    &hostname,
                    socklen_t(hostname.count),
                    nil,
                    0,
                    NI_NUMERICHOST
                ) == 0 {
                    let ip = String(cString: hostname)
                    let name = String(cString: interface.ifa_name)
                    // en0 是 iPhone 的 Wi-Fi 主接口；模拟器回 lo0 已排除（非回环判断）
                    if ip != "127.0.0.1", name == "en0" || name.hasPrefix("en") {
                        address = ip
                        if name == "en0" { break }
                    }
                }
            }
            ptr = current.pointee.ifa_next
        }
        return address
    }
}
