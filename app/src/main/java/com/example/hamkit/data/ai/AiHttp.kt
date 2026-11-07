package com.example.hamkit.data.ai

import com.example.hamkit.data.network.HttpClientProvider
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
 * manifest 层只负责让内网 http 不被系统直接拒绝。
 *
 * 自签 HTTPS：只有用户显式开启 [AiConfig.allowSelfSigned] 且 host 是私有地址时，
 * 才使用 trustAll 客户端；公网仍走系统证书校验。
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

    fun streaming(allowSelfSigned: Boolean): OkHttpClient =
        if (allowSelfSigned) trustAllStreaming else streaming

    fun blocking(allowSelfSigned: Boolean): OkHttpClient =
        if (allowSelfSigned) trustAllBlocking else blocking

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

    /** 仅用于内网自签证书；调用方必须保证 allowSelfSigned + 私有地址。 */
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
