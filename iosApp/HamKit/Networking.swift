import Foundation
import Shared

/// 用系统 `URLSession` 实现共享内核要求的 `HttpTransport`。
///
/// 放在 Swift 侧的原因：TLS / ATS / 代理 / 证书校验全部走 iOS 系统默认行为，
/// 不需要在 Kotlin/Native 里写 cinterop，也不会把网络策略固化进 commonMain。
final class URLSessionTransport: NSObject, HttpTransport {

    private let userAgent = "HamKit-iOS/0.1 (+https://hamkit.click)"

    func get(url: String, timeoutMs: Int32, callback: @escaping (HttpResponse) -> Void) {
        guard let requestUrl = URL(string: url) else {
            callback(HttpResponse(status: 0, body: nil, error: "URL 非法：\(url)"))
            return
        }

        var request = URLRequest(url: requestUrl)
        request.httpMethod = "GET"
        request.timeoutInterval = TimeInterval(timeoutMs) / 1000.0
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        // 不写死 Accept: 和 Android 的 OkHttp 一样交给默认值（*/*）。
        // 之前固定成 text/plain，对 TLE 没影响，但对 JSON 接口（SatNOGS / hitokoto）
        // 是不必要的内容协商风险。

        URLSession.shared.dataTask(with: request) { data, response, error in
            let result: HttpResponse
            if let error = error {
                result = HttpResponse(status: 0, body: nil, error: error.localizedDescription)
            } else if let http = response as? HTTPURLResponse {
                let body = data.flatMap { String(data: $0, encoding: .utf8) }
                let ok = (200...299).contains(http.statusCode)
                result = HttpResponse(
                    status: Int32(http.statusCode),
                    body: body,
                    error: ok ? nil : "HTTP \(http.statusCode)"
                )
            } else {
                result = HttpResponse(status: 0, body: nil, error: "没有收到 HTTP 响应")
            }
            // 共享内核约定回调可在任意线程；统一切回主线程，UI 直接刷新即可
            DispatchQueue.main.async { callback(result) }
        }.resume()
    }
}
