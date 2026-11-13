package com.example.hamkit.data.ai

import com.example.hamkit.data.network.HttpClientProvider
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/**
 * AI 专用 HTTP 栈。
 *
 * 为什么不用全局 [HttpClientProvider.client]：流式响应可能持续数十秒没有新数据，
 * 全局 30s 读超时会误杀。这里单独派生：
 * - 读超时 60s（收到任意数据就会重置，等价于"空闲 60s 视为超时"）；
 * - 连接超时沿用 15s。
 *
 * 明文策略不在这里做（OkHttp 不拦 scheme），而是在请求前由 [AiNetworkPolicy] 校验；
 * manifest 层不放开明文（base-config 已恢复默认，见 network_security_config.xml），
 * 公网明文由 [AiNetworkPolicy] 拒绝，内网明文走 OkHttp 不拦 scheme 的行为。
 *
 * 自签 HTTPS（2026-11 安全审计收紧）：
 * trustAll 客户端**取消一切证书与主机名校验**，一旦用于非预期目标就把 TLS 的
 * 中间人防线整个拆掉。因此把「仅限私有地址」从调用方约定升级为代码强制：
 * [streaming]/[blocking] 在返回 trustAll 客户端前会解析请求 URL，host 不是
 * 私有地址时直接抛 [IllegalArgumentException] 而不是静默放行。
 */
object AiHttp {

    val streaming: OkHttpClient by lazy {
        HttpClientProvider.client.newBuilder()
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** 非流式请求（连接测试）：恢复常规读超时。 */
    val blocking: OkHttpClient by lazy {
        HttpClientProvider.client.newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val trustAllStreaming: OkHttpClient by lazy { trustAll(streaming) }
    private val trustAllBlocking: OkHttpClient by lazy { trustAll(blocking) }

    /**
     * 流式客户端选择。
     *
     * @param allowSelfSigned 是否信任自签证书（仅私网生效）
     * @param url 实际请求地址；用于强制「trustAll 仅限私有地址」校验。
     *            传 null 表示调用方无法提供地址（此时不做 trustAll，回退标准客户端）。
     */
    fun streaming(allowSelfSigned: Boolean, url: HttpUrl? = null): OkHttpClient =
        resolve(allowSelfSigned, url) { trustAllStreaming }

    /** 非流式客户端选择，私网约束同 [streaming]。 */
    fun blocking(allowSelfSigned: Boolean, url: HttpUrl? = null): OkHttpClient =
        resolve(allowSelfSigned, url) { trustAllBlocking }

    private inline fun resolve(
        allowSelfSigned: Boolean,
        url: HttpUrl?,
        trustAllClient: () -> OkHttpClient,
    ): OkHttpClient {
        if (!allowSelfSigned) return trustAllClient()
        // allowSelfSigned=true 时必须能证明目标是私有地址，否则拒绝降级到 trustAll。
        val host = url?.host
        require(!host.isNullOrBlank() && AiNetworkPolicy.isPrivateHost(host)) {
            "trustAll SSL 仅允许私有地址（host=${host ?: "<unknown>"}），" +
                "公网地址必须使用系统证书校验"
        }
        return trustAllClient()
    }

    /**
     * 取消当前所有 AI HTTP 请求（用于「停止生成」）。
     *
     * OkHttp 的阻塞读不会随协程取消而中断，必须显式 cancelAll，
     * 让 streamRequest 里的 IOException("Canceled") 转成 CancellationException。
     */
    fun cancelActive() {
        runCatching { streaming.dispatcher.cancelAll() }
        runCatching { blocking.dispatcher.cancelAll() }
        runCatching { trustAllStreaming.dispatcher.cancelAll() }
        runCatching { trustAllBlocking.dispatcher.cancelAll() }
    }

    /** 仅用于内网自签证书；调用方必须保证 allowSelfSigned + 私有地址（resolve 已强制）。 */
    private fun trustAll(base: OkHttpClient): OkHttpClient {
        val manager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(manager), SecureRandom())
        return base.newBuilder()
            .sslSocketFactory(context.socketFactory, manager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }
}
