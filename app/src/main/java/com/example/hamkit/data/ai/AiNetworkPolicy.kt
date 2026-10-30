package com.example.hamkit.data.ai

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 内网 http 的白名单策略。
 *
 * 背景：Android 的 network-security-config 是静态的，`<domain-config>` 不支持
 * CIDR / IP 通配，无法"只对某个内网段放开明文"。因此在 manifest 层放开明文后，
 * 由本对象在业务层把范围收窄到：**用户显式开启开关 + host 是私有地址**。
 *
 * 这样即使 manifest 允许明文，公网 http 依然会被拒绝（例如把
 * `http://api.deepseek.com` 填进 Base URL）。
 */
object AiNetworkPolicy {

    /** 私有地址段：10/8、172.16/12、192.168/16、localhost、127.0.0.1。 */
    private val PRIVATE_HOST = Regex(
        "^(localhost|127\\.0\\.0\\.1" +
            "|10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}" +
            "|172\\.(1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3}" +
            "|192\\.168\\.\\d{1,3}\\.\\d{1,3})$"
    )

    fun isPrivateHost(host: String): Boolean {
        val h = host.trim().lowercase()
        return PRIVATE_HOST.matches(h) || h.endsWith(".local") || h.endsWith(".lan")
    }

    /**
     * 校验 Base URL。
     *
     * @return null 表示允许；否则返回可直接展示给用户的原因。
     */
    fun validate(baseUrl: String, allowCleartext: Boolean): String? {
        val url = baseUrl.trim().toHttpUrlOrNull() ?: return "Base URL 格式不正确"
        if (url.isHttps) return null
        if (!allowCleartext) {
            return "http 需要先在「AI 设置 → 网络」中开启「允许明文 HTTP（仅内网）」"
        }
        if (!isPrivateHost(url.host)) {
            return "只允许内网地址使用 http：${url.host}"
        }
        return null
    }
}
