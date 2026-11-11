package com.example.hamkit.ai

/**
 * 内网地址判定（对齐 Android `AiNetworkPolicy.isPrivateHost`）。
 *
 * 用途：「信任自签 HTTPS」只在私有地址上生效——公网域名即使开了开关，
 * 也仍走系统证书校验，避免把 MITM 风险带到公网。
 *
 * 内核要同时跑在 JVM 与 Kotlin/Native 上，不做 DNS 解析：只识别
 * IP 字面量与 `.local` / `.lan` 后缀（mDNS 主机名）。
 */
object AiNetworkPolicy {

    /** 私有地址段：10/8、172.16/12、192.168/16、localhost、127.x、CGNAT 100.64/10。 */
    private val PRIVATE_HOST = Regex(
        "^(localhost" +
            "|127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}" +
            "|10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}" +
            "|172\\.(1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3}" +
            "|192\\.168\\.\\d{1,3}\\.\\d{1,3}" +
            "|100\\.(6[4-9]|[7-9]\\d|1[01]\\d|12[0-7])\\.\\d{1,3}\\.\\d{1,3})$",
    )

    fun isPrivateHost(host: String): Boolean {
        val h = host.trim().lowercase()
        return PRIVATE_HOST.matches(h) || h.endsWith(".local") || h.endsWith(".lan")
    }
}
