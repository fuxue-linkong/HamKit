package com.example.hamkit.ui.viewmodel

import com.example.hamkit.data.ft8.Ft8Band
import com.example.hamkit.data.ft8.Ft8QsoRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DeletedRecordBuffer] 单元测试：缓冲 / 撤销 / 10 秒超时 / 多条记录互不干扰。
 *
 * 用 runTest + advanceTimeBy 驱动虚拟时间，无需真实等待。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeletedRecordBufferTest {

    private fun record(id: Long, callsign: String = "BG7S") = Ft8QsoRecord(
        id = id,
        callsign = callsign,
        grid = null,
        band = Ft8Band.BAND_20M,
        qsoTime = 1_700_000_000_000L,
    )

    @Test
    fun `buffer returns seq and record stays buffered`() = runTest {
        val expired = mutableListOf<Ft8QsoRecord>()
        val buffer = DeletedRecordBuffer(scope = backgroundScope, onExpire = { expired.add(it) })

        val seq = buffer.buffer(record(1))

        assertTrue(seq > 0)
        assertTrue(buffer.isBuffered(1))
        assertEquals(listOf(record(1)), buffer.bufferedRecords())
        assertTrue(expired.isEmpty())
    }

    @Test
    fun `restore before timeout returns record and cancels expiry`() = runTest {
        val expired = mutableListOf<Ft8QsoRecord>()
        val buffer = DeletedRecordBuffer(scope = backgroundScope, onExpire = { expired.add(it) })
        val seq = buffer.buffer(record(2))

        // 9 秒后撤销（未到 10 秒）
        advanceTimeBy(9_000)
        val restored = buffer.restore(seq)

        assertEquals(record(2), restored)
        assertFalse(buffer.isBuffered(2))
        // 撤销后再推进远超 10 秒，也不应触发过期删除
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(expired.isEmpty())
    }

    @Test
    fun `restore after timeout returns null`() = runTest {
        val expired = mutableListOf<Ft8QsoRecord>()
        val buffer = DeletedRecordBuffer(scope = backgroundScope, onExpire = { expired.add(it) })
        val seq = buffer.buffer(record(3))

        advanceTimeBy(10_000)
        runCurrent()

        assertTrue(expired.contains(record(3)))
        assertNull(buffer.restore(seq))
    }

    @Test
    fun `expires exactly after timeout and calls onExpire`() = runTest {
        val expired = mutableListOf<Ft8QsoRecord>()
        val buffer = DeletedRecordBuffer(scope = backgroundScope, onExpire = { expired.add(it) })
        buffer.buffer(record(4))

        // 9_999ms 未超时
        advanceTimeBy(9_999)
        runCurrent()
        assertTrue("9.999s 不应超时", expired.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(record(4)), expired)
        assertFalse(buffer.isBuffered(4))
    }

    @Test
    fun `custom timeout is honored`() = runTest {
        val expired = mutableListOf<Ft8QsoRecord>()
        val buffer = DeletedRecordBuffer(
            timeoutMillis = 2_000,
            scope = backgroundScope,
            onExpire = { expired.add(it) },
        )
        buffer.buffer(record(5))

        advanceTimeBy(1_999)
        runCurrent()
        assertTrue(expired.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(record(5)), expired)
    }

    @Test
    fun `multiple records expire independently`() = runTest {
        val expired = mutableListOf<Ft8QsoRecord>()
        val buffer = DeletedRecordBuffer(scope = backgroundScope, onExpire = { expired.add(it) })

        val seqA = buffer.buffer(record(10, "BA1AA"))
        advanceTimeBy(4_000)
        val seqB = buffer.buffer(record(11, "BA2BB"))

        // 第 10 秒：A 到期，B（第 4 秒入缓冲）还差 4 秒
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(listOf(record(10, "BA1AA")), expired)
        assertTrue(buffer.isBuffered(11))

        // 撤销 B
        val restored = buffer.restore(seqB)
        assertEquals(record(11, "BA2BB"), restored)
        advanceTimeBy(30_000)
        runCurrent()
        // B 被撤销，不再过期；A 只过期一次
        assertEquals(listOf(record(10, "BA1AA")), expired)
        assertNull(buffer.restore(seqA))
    }

    @Test
    fun `restoreLast returns most recently buffered`() = runTest {
        val buffer = DeletedRecordBuffer(scope = backgroundScope)
        buffer.buffer(record(20, "BA1AA"))
        val seqLast = buffer.buffer(record(21, "BA2BB"))

        val restored = buffer.restoreLast()
        assertEquals(record(21, "BA2BB"), restored)
        assertFalse(buffer.isBuffered(21))
        assertTrue(buffer.isBuffered(20))
        // 再撤销一次拿到最早那条
        assertEquals(record(20, "BA1AA"), buffer.restoreLast())
        assertNull(buffer.restoreLast())
        // seq 撤销接口与栈顶接口一致：已恢复的 seq 再 restore 返回 null
        assertNull(buffer.restore(seqLast))
    }

    @Test
    fun `rebuffering same id supersedes old entry without double expire`() = runTest {
        val expired = mutableListOf<Ft8QsoRecord>()
        val buffer = DeletedRecordBuffer(scope = backgroundScope, onExpire = { expired.add(it) })

        buffer.buffer(record(30))
        advanceTimeBy(9_000)
        // 同 id 再次删除（恢复后又删的极端序列）：旧条目作废
        buffer.buffer(record(30))

        advanceTimeBy(60_000)
        runCurrent()
        // 只有新条目按自己的 10 秒到期，且只触发一次
        assertEquals(1, expired.size)
        assertEquals(record(30), expired.first())
    }

    @Test
    fun `clear empties buffer and cancels pending expiries`() = runTest {
        val expired = mutableListOf<Ft8QsoRecord>()
        val buffer = DeletedRecordBuffer(scope = backgroundScope, onExpire = { expired.add(it) })
        buffer.buffer(record(40))
        buffer.buffer(record(41, "BA2BB"))

        buffer.clear()

        assertTrue(buffer.bufferedRecords().isEmpty())
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(expired.isEmpty())
    }
}
