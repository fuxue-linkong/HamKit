import Foundation
import Shared

/// 共享内核的流式 HTTP 实现：URLSession + 增量 UTF-8 解码。
///
/// 内核把「协议 + SSE 分帧」都做完了，这里只负责把响应体当文本流送进去。
/// 两个坑必须处理：
/// 1. **UTF-8 跨分片**：`didReceive data` 的分片边界可能落在一个汉字中间，
///    直接 `String(data:encoding:)` 会解出乱码，所以按「最后一个换行」切分，
///    只把完整行交给内核，残余字节留到下一次（SSE 帧一定以换行结尾）；
/// 2. **HTTP 错误**：非 2xx 时不进入 SSE 解析，收完 body 后按错误回调，
///    否则内核会拿一段错误 JSON 去当事件流解析。
final class AiURLSessionTransport: NSObject, AiStreamTransport {

    private let configuration: URLSessionConfiguration

    override init() {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 120
        config.timeoutIntervalForResource = 300
        // 关掉压缩：SSE 需要增量到达，交给系统解压会在代理/网关下攒包
        config.httpAdditionalHeaders = ["Accept-Encoding": "identity"]
        configuration = config
        super.init()
    }

    func start(request: AiHttpRequest, listener: AiStreamListener) -> AiRequestHandle {
        guard let url = URL(string: request.url) else {
            listener.onFailed(error: AiBridge.shared.networkError(detail: "URL 无效：\(request.url)"))
            return AiStreamHandle(task: nil, session: nil)
        }

        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = "POST"
        urlRequest.timeoutInterval = TimeInterval(request.timeoutSeconds)
        urlRequest.httpBody = request.body.data(using: .utf8)
        for (key, value) in request.headers {
            urlRequest.setValue(value, forHTTPHeaderField: key)
        }

        // 每个请求一个 session：delegate 由 session 强引用，回调不会串到别的请求上
        let delegate = AiStreamDelegate(listener: listener)
        let session = URLSession(
            configuration: configuration,
            delegate: delegate,
            delegateQueue: nil
        )
        delegate.session = session
        let task = session.dataTask(with: urlRequest)
        delegate.task = task
        task.resume()
        return AiStreamHandle(task: task, session: session)
    }
}

/// 取消句柄：取消任务并让 session 失效（否则 session 会一直持有 delegate）。
///
/// 这里**强引用** task 与 session：URLSession 只有在被引用时才保证生命周期，
/// 若只留弱引用，流式请求可能在中途被回收（表现为「没有任何回调」）。
private final class AiStreamHandle: NSObject, AiRequestHandle {

    private var task: URLSessionDataTask?
    private var session: URLSession?

    init(task: URLSessionDataTask?, session: URLSession?) {
        self.task = task
        self.session = session
        super.init()
    }

    func cancel() {
        task?.cancel()
        session?.invalidateAndCancel()
        task = nil
        session = nil
    }
}

private final class AiStreamDelegate: NSObject, URLSessionDataDelegate {

    private let listener: AiStreamListener
    private var buffer = Data()
    private var errorBody = Data()
    private var statusCode = 0
    private var finished = false
    weak var task: URLSessionDataTask?
    weak var session: URLSession?

    init(listener: AiStreamListener) {
        self.listener = listener
        super.init()
    }

    func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        statusCode = (response as? HTTPURLResponse)?.statusCode ?? 0
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard !finished else { return }
        if statusCode != 0 && !(200..<300).contains(statusCode) {
            // 错误响应体只留前 8KB，够解析出 message 就行
            if errorBody.count < 8192 { errorBody.append(data) }
            return
        }

        buffer.append(data)
        // 只把「以换行结尾」的完整部分交出去：保证多字节字符不被截断
        guard buffer.last == 0x0A else { return }
        let text = String(decoding: buffer, as: UTF8.self)
        buffer.removeAll(keepingCapacity: true)
        if !text.isEmpty { listener.onChunk(text: text) }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard !finished else { return }
        finished = true
        session.finishTasksAndInvalidate()

        if let error = error as NSError? {
            if error.domain == NSURLErrorDomain && error.code == NSURLErrorCancelled {
                listener.onFailed(error: AiBridge.shared.cancelledError())
            } else if error.domain == NSURLErrorDomain && error.code == NSURLErrorTimedOut {
                listener.onFailed(error: AiBridge.shared.networkError(detail: "请求超时"))
            } else {
                listener.onFailed(
                    error: AiBridge.shared.networkError(detail: error.localizedDescription)
                )
            }
            return
        }

        if statusCode != 0 && !(200..<300).contains(statusCode) {
            let body = String(decoding: errorBody, as: UTF8.self)
            listener.onFailed(
                error: AiBridge.shared.httpError(status: Int32(statusCode), body: body)
            )
            return
        }

        // 收尾：残余半行也交给内核（它会在 flush 时处理）
        if !buffer.isEmpty {
            let text = String(decoding: buffer, as: UTF8.self)
            buffer.removeAll(keepingCapacity: true)
            if !text.isEmpty { listener.onChunk(text: text) }
        }
        listener.onCompleted()
    }
}
