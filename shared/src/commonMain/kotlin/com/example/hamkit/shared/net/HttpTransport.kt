package com.example.hamkit.shared.net

/**
 * 平台无关的 HTTP GET 抽象。
 *
 * 为什么不用 Ktor：iOS 端只需要「拉一个文本」，引入 Ktor + Darwin 引擎会带来额外
 * 依赖与二进制体积；把网络实现交给平台原生栈（Android: OkHttp / HttpURLConnection，
 * iOS: URLSession）反而更贴合各平台的 TLS、代理、证书与企业网络策略。
 *
 * 线程约定：实现可以在任意线程回调；iOS 壳的 `URLSessionTransport` 会切回主线程。
 */
interface HttpTransport {

    /**
     * 发起一次 GET 请求。
     *
     * @param url 完整 URL
     * @param timeoutMs 超时（毫秒）
     * @param callback 结果回调；失败时 [HttpResponse.error] 非空，不会抛异常
     */
    fun get(url: String, timeoutMs: Int, callback: (HttpResponse) -> Unit)
}

/**
 * HTTP 响应（或失败原因）。
 *
 * 刻意不暴露异常：Kotlin/Native 的异常跨语言会直接终止进程，网络这种
 * 「预期内会失败」的路径必须用值来表达。
 */
data class HttpResponse(
    /** HTTP 状态码；0 表示请求没有拿到响应（DNS/连接/超时） */
    val status: Int,
    /** 响应体文本；失败时为 null */
    val body: String?,
    /** 失败原因；成功时为 null */
    val error: String?,
) {
    val isSuccess: Boolean
        get() = status in 200..299 && body != null && error == null
}
