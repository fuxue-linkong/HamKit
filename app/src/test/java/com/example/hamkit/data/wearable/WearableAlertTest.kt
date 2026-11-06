package com.example.hamkit.data.wearable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [WearableAlert] 协议编解码测试。
 *
 * 两端是独立工程（Kotlin ↔ JS），字段名/结构一旦漂移，表现是"手环收不到提醒"，
 * 在真机上极难定位，所以把协议形态锁在单测里。
 *
 * 用 Robolectric 跑：序列化走 `org.json`，纯 JVM 下 android.jar 是空壳会抛
 * "not mocked"。仓库里其它 org.json 相关的单测也是这个套路。
 */
@RunWith(RobolectricTestRunner::class)
class WearableAlertTest {

    private fun sampleAlert(aos: Long = 1_700_000_000_000L) = WearableAlert(
        id = WearableAlert.makeId(25544, aos),
        sat = "ISS",
        norad = 25544,
        aos = aos,
        los = aos + 600_000L,
        el = 42,
        az = 128,
        modes = listOf("FM"),
        now = aos - 60_000L
    )

    @Test
    fun `toJson keeps the fields the watch reads`() {
        val json = sampleAlert().toJson()

        // 手环端 alert.js 依赖这几个字段：t / v / id / sat / aos / now
        assertTrue("缺少类型字段: $json", json.contains("\"t\":\"pass\""))
        assertTrue("缺少协议版本: $json", json.contains("\"v\":1"))
        assertTrue("缺少卫星名: $json", json.contains("\"sat\":\"ISS\""))
        assertTrue("缺少 id: $json", json.contains("\"id\":\"25544-1700000000000\""))
        assertTrue("缺少 AOS: $json", json.contains("\"aos\":1700000000000"))
        assertTrue("缺少发送时刻: $json", json.contains("\"now\":1699999940000"))
        assertTrue("缺少模式: $json", json.contains("\"modes\":[\"FM\"]"))
    }

    @Test
    fun `makeId combines norad and aos`() {
        assertEquals("25544-1700000000000", WearableAlert.makeId(25544, 1_700_000_000_000L))
    }

    @Test
    fun `decodeBytes parses ack from watch`() {
        val raw = """{"t":"ack","v":1,"id":"25544-1700000000000","act":"dismiss","at":123}"""

        val msg = WearableInbound.decodeBytes(raw.toByteArray())

        assertNotNull(msg)
        assertEquals(WearableInbound.TYPE_ACK, msg!!.t)
        assertEquals("dismiss", msg.act)
        assertEquals("25544-1700000000000", msg.id)
        assertEquals(123L, msg.at)
    }

    @Test
    fun `decodeBytes parses hello without optional fields`() {
        val msg = WearableInbound.decodeBytes("""{"t":"hello"}""".toByteArray())

        assertNotNull(msg)
        assertEquals(WearableInbound.TYPE_HELLO, msg!!.t)
        assertNull(msg.id)
        assertNull(msg.at)
    }

    @Test
    fun `decodeBytes unwraps double encoded payload`() {
        // 手环端 connect.send({data: obj}) 在某些 toolkit 版本下会多包一层字符串，
        // 手机上收到的就是 "{\"t\":\"hello\"}" 这种形态
        val doubleEncoded = "\"{\\\"t\\\":\\\"hello\\\",\\\"v\\\":1}\""

        val msg = WearableInbound.decodeBytes(doubleEncoded.toByteArray())

        assertNotNull(msg)
        assertEquals(WearableInbound.TYPE_HELLO, msg!!.t)
    }

    @Test
    fun `decodeBytes ignores unknown fields`() {
        val msg = WearableInbound.decodeBytes("""{"t":"ack","futureField":true}""".toByteArray())

        assertNotNull(msg)
        assertEquals(WearableInbound.TYPE_ACK, msg!!.t)
    }

    @Test
    fun `decodeBytes returns null for garbage and missing type`() {
        assertNull(WearableInbound.decodeBytes("not a json".toByteArray()))
        assertNull(WearableInbound.decodeBytes("".toByteArray()))
        // 缺 t 字段：手环端不会发这种包，忽略而不是崩溃
        assertNull(WearableInbound.decodeBytes("""{"v":1}""".toByteArray()))
    }
}
