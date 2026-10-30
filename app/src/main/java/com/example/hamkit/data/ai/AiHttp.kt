package com.example.hamkit.data.ai

import com.example.hamkit.data.network.HttpClientProvider
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * AI 专用 HTTP 栈。
 *
 * 为什么不直接用 [HttpClientProvider.client]：流式响应可能持续数十秒没有新数据，
 * 全局 30s 读超时会误杀。这里单独派生：
 * - 读超时 60s（**收到任意数据就会重置**，等价于"空闲 60s 视为超时"）；
 * - 连接超时沿用 15s。
 *
 * 明文策略不在这里做（OkHttp 不拦 scheme），而是请求前由 [AiNetworkPolicy] 校验；
 * manifest 层只负责让内网 http 不被系统直接拒绝。
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
}
