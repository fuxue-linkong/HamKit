package com.example.hamkit.shared

import com.example.hamkit.data.satellite.tle.TleClient
import com.example.hamkit.data.satellite.tle.TleEndpoints
import com.example.hamkit.data.satellite.tle.TleFetchOutcome
import com.example.hamkit.data.satellite.tle.TleGroup
import com.example.hamkit.data.satellite.tle.TleRepository
import com.example.hamkit.data.satellite.tle.TleSnapshot
import com.example.hamkit.data.satellite.tle.TleTextParser
import com.example.hamkit.shared.net.HttpResponse
import com.example.hamkit.shared.net.HttpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网络 TLE 拉取链路：文本解析 → 多源客户端（CDN → CelesTrak 兜底）→ 缓存仓库 → HamKitApi。
 *
 * 全部用同步 FakeTransport，不碰真实网络，因此可以离线跑。
 */
class TleFeedTest {

    private val issName = "ISS (ZARYA)"
    private val issLine1 = "1 25544U 98067A   26220.50489838  .00004539  00000+0  89319-4 0  9991"
    private val issLine2 = "2 25544  51.6323  41.1734 0007358  25.6275 334.5077 15.49385107579869"
    private val qoName = "ESH'HAIL-2"
    private val qoLine1 = "1 43700U 18090A   26220.35824306 -.00000275  00000+0  00000+0 0  9997"
    private val qoLine2 = "2 43700   0.0213 318.9716 0002679 126.3766 197.7513  1.00270839 26574"

    private fun sampleText(): String = buildString {
        appendLine(issName)
        appendLine(issLine1)
        appendLine(issLine2)
        appendLine()
        appendLine(qoName)
        appendLine(qoLine1)
        appendLine(qoLine2)
        appendLine()
    }

    // ── 解析 ──

    @Test
    fun `parse - 两条 TLE 正确切分`() {
        val records = TleTextParser.parse(sampleText())
        assertEquals(2, records.size)
        assertEquals("ISS (ZARYA)", records[0].name)
        assertEquals(25544, records[0].norad)
        assertEquals("ESH'HAIL-2", records[1].name)
        assertEquals(43700, records[1].norad)
        assertEquals(listOf(issName, issLine1, issLine2), records[0].lines)
        assertNotNull("解析结果应能还原成轨道元素", records[0].elementsOrNull())
    }

    @Test
    fun `parse - 缺名称时用 NORAD 兜底`() {
        val text = "$issLine1\n$issLine2\n"
        val records = TleTextParser.parse(text)
        assertEquals(1, records.size)
        assertEquals("NORAD 25544", records[0].name)
    }

    @Test
    fun `parse - 长度不足或字段非法的记录被跳过`() {
        val broken = "1 99999U short" // 明显不是合法的 69 列 TLE
        val text = "$broken\n$issLine2\n\n${issName}\n$issLine1\n$issLine2\n"
        val records = TleTextParser.parse(text)
        assertEquals("坏行应被丢掉，只剩合法的一条", 1, records.size)
        assertEquals(25544, records[0].norad)
    }

    @Test
    fun `parse - 同一 NORAD 重复出现保留最后一条`() {
        val renamed = "ISS (ZARYA) v2"
        val text = "${issName}\n$issLine1\n$issLine2\n\n$renamed\n$issLine1\n$issLine2\n"
        val records = TleTextParser.parse(text)
        assertEquals(1, records.size)
        assertEquals("ISS (ZARYA) v2", records[0].name)
    }

    // ── 地址 ──

    @Test
    fun `endpoints - 自建 CDN 优先，CelesTrak 兜底`() {
        val urls = TleEndpoints().urls(TleGroup.AMATEUR)
        assertEquals(2, urls.size)
        assertEquals("https://tle.hamkit.click/tle/amateur.3le", urls[0])
        assertEquals(
            "https://celestrak.org/NORAD/elements/gp.php?GROUP=amateur&FORMAT=tle",
            urls[1]
        )
    }

    // ── 客户端 ──

    @Test
    fun `client - CDN 成功时只请求一次`() {
        val transport = FixedTransport(HttpResponse(200, sampleText(), null))
        var outcome: TleFetchOutcome? = null
        TleClient(transport).fetch(TleGroup.AMATEUR) { outcome = it }

        assertEquals(2, outcome?.records?.size)
        assertNull(outcome?.error)
        assertEquals(1, transport.callCount)
        assertTrue(transport.urls.single().endsWith("/amateur.3le"))
    }

    @Test
    fun `client - CDN 失败后自动回落到 CelesTrak`() {
        val transport = RoutingTransport { url ->
            if (url.contains("tle.hamkit.click")) HttpResponse(0, null, "连接超时")
            else HttpResponse(200, sampleText(), null)
        }
        var outcome: TleFetchOutcome? = null
        TleClient(transport).fetch(TleGroup.AMATEUR) { outcome = it }

        assertEquals(2, transport.urls.size)
        assertTrue(transport.urls[1].contains("celestrak.org"))
        assertEquals(2, outcome?.records?.size)
        assertNull(outcome?.error)
    }

    @Test
    fun `client - 全部地址失败时返回带原因的文案`() {
        val transport = FixedTransport(HttpResponse(503, null, "HTTP 503"))
        var outcome: TleFetchOutcome? = null
        TleClient(transport).fetch(TleGroup.AMATEUR) { outcome = it }

        assertEquals(2, transport.callCount)
        assertTrue(outcome?.records.isNullOrEmpty())
        assertTrue(outcome?.error!!.contains("HTTP 503"))
    }

    @Test
    fun `client - 200 但内容不可解析时继续尝试下一个地址`() {
        val transport = RoutingTransport { url ->
            if (url.contains("tle.hamkit.click")) HttpResponse(200, "<html>rate limited</html>", null)
            else HttpResponse(200, sampleText(), null)
        }
        var outcome: TleFetchOutcome? = null
        TleClient(transport).fetch(TleGroup.AMATEUR) { outcome = it }

        assertEquals(2, transport.callCount)
        assertEquals(2, outcome?.records?.size)
    }

    // ── 缓存仓库 ──

    @Test
    fun `repository - TTL 内命中缓存且不重复请求`() {
        val transport = FixedTransport(HttpResponse(200, sampleText(), null))
        val repo = TleRepository(TleClient(transport))
        val t0 = 1_000_000L

        var first: TleSnapshot? = null
        repo.load(TleGroup.AMATEUR, forceRefresh = false, nowMs = t0) { first = it }
        var second: TleSnapshot? = null
        repo.load(TleGroup.AMATEUR, forceRefresh = false, nowMs = t0 + 60_000) { second = it }

        assertFalse(first!!.fromCache)
        assertTrue(second!!.fromCache)
        assertEquals(t0, second!!.fetchedAtMs)
        assertEquals("只应请求一次", 1, transport.callCount)
    }

    @Test
    fun `repository - 超过 TTL 后重新拉取`() {
        val transport = FixedTransport(HttpResponse(200, sampleText(), null))
        val repo = TleRepository(TleClient(transport), ttlMs = 1_000L)
        val t0 = 1_000_000L

        repo.load(TleGroup.AMATEUR, false, t0) {}
        repo.load(TleGroup.AMATEUR, false, t0 + 1_001) {}

        assertEquals(2, transport.callCount)
    }

    @Test
    fun `repository - 网络失败回退到上次缓存`() {
        val transport = FixedTransport(HttpResponse(200, sampleText(), null))
        val repo = TleRepository(TleClient(transport), ttlMs = 1_000L)
        val t0 = 1_000_000L
        repo.load(TleGroup.AMATEUR, false, t0) {}

        transport.response = HttpResponse(0, null, "连接超时")
        var failed: TleSnapshot? = null
        repo.load(TleGroup.AMATEUR, forceRefresh = true, nowMs = t0 + 10_000) { failed = it }

        assertEquals(2, failed!!.records.size)
        assertTrue(failed!!.fromCache)
        assertTrue(failed!!.error!!.contains("连接超时"))
    }

    // ── HamKitApi 入口 ──

    @Test
    fun `api - 安装 transport 后可拉取并带出时间戳`() {
        HamKitApi.installHttpTransport(FixedTransport(HttpResponse(200, sampleText(), null)))
        var snapshot: TleListSnapshot? = null
        HamKitApi.fetchTles("amateur", forceRefresh = true, nowMs = 42L) { snapshot = it }

        assertEquals(2, snapshot!!.records.size)
        assertNull(snapshot!!.error)
        assertEquals(42L, snapshot!!.fetchedAtMs)
    }

    private class FixedTransport(var response: HttpResponse) : HttpTransport {
        var urls = mutableListOf<String>()
        val callCount: Int get() = urls.size

        override fun get(url: String, timeoutMs: Int, callback: (HttpResponse) -> Unit) {
            urls += url
            callback(response)
        }
    }

    private class RoutingTransport(private val handler: (String) -> HttpResponse) : HttpTransport {
        var urls = mutableListOf<String>()
        val callCount: Int get() = urls.size

        override fun get(url: String, timeoutMs: Int, callback: (HttpResponse) -> Unit) {
            urls += url
            callback(handler(url))
        }
    }
}
