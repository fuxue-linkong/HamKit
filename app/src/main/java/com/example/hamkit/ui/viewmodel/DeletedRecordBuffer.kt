package com.example.hamkit.ui.viewmodel

import com.example.hamkit.data.ft8.Ft8QsoRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * 已删除 QSO 记录的内存缓冲：删除先进缓冲区等 10 秒，期间可撤销，超时才真正落库删除。
 *
 * 设计要点：
 * - 纯 Kotlin（无 Android 依赖），单测用 [MutableTestClock] 或注入 [now] 控制时间；
 * - 超时判定用时钟而非挂钟轮询：每条记录带 [Job]，超时后 [onExpire] 在缓冲协程作用域触发；
 * - 撤销（[restore]）取消该记录的超时任务并移出缓冲，UI 把记录重新写回数据库；
 * - 多条记录互不影响：各自独立倒计时，撤销/过期互不干扰。
 *
 * 仅缓冲单条记录对象，不持久化——进程被杀时缓冲区丢失，此时记录已从 DB 删除，
 * 行为等同"删除完成"，不会产生幽灵记录。
 */
class DeletedRecordBuffer(
    /** 超时时长，默认 10 秒；测试可注入更小值 */
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    /** 时钟函数，测试可注入固定/可控时钟 */
    now: () -> Long = { System.currentTimeMillis() },
    /** 缓冲条目过期（真正删除）时回调，参数为过期记录 */
    private val onExpire: (Ft8QsoRecord) -> Unit = {},
    /** 协程作用域：承载超时倒计时；ViewModel 传 viewModelScope，测试可传 TestScope */
    private val scope: CoroutineScope,
) {

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 10_000L
    }

    /** 缓冲条目：记录本体 + 入缓冲时间戳 + 超时任务句柄 */
    private class Entry(
        val record: Ft8QsoRecord,
        val bufferedAt: Long,
        var expiryJob: Job? = null,
    )

    /** 单调自增序号：实现"后删先撤销"的栈语义（撤销最近一次删除） */
    private val seqGenerator = AtomicLong(0)

    private val entries = LinkedHashMap<Long, Entry>()

    /** 暴露给测试的时间函数；构造参数缺省为系统时钟 */
    private val clock: () -> Long = now

    /**
     * 记录入缓冲。若同 id 记录已在缓冲（连续删除/恢复又删除的极端序列），先取消旧任务再覆盖。
     * 返回该条目的序号，供 [restore] 精确撤销对应条目。
     */
    @Synchronized
    fun buffer(record: Ft8QsoRecord): Long {
        val seq = seqGenerator.incrementAndGet()
        // 同 id 旧条目直接作废：其超时任务取消，避免双重 onExpire
        entries.values.removeAll { it.record.id == record.id && it.expiryJob != null }
        val entry = Entry(record, clock())
        entry.expiryJob = scope.launch {
            delay(timeoutMillis)
            expire(seq)
        }
        entries[seq] = entry
        return seq
    }

    /**
     * 撤销删除：把 [seq] 对应的记录移出缓冲并取消超时任务。
     * 返回恢复的记录；缓冲区中已不存在（已超时删除/已恢复过）时返回 null。
     */
    @Synchronized
    fun restore(seq: Long): Ft8QsoRecord? {
        val entry = entries.remove(seq) ?: return null
        entry.expiryJob?.cancel()
        return entry.record
    }

    /**
     * 撤销最近一次缓冲的删除（栈顶语义），成功返回记录。
     */
    @Synchronized
    fun restoreLast(): Ft8QsoRecord? {
        val seq = entries.keys.lastOrNull() ?: return null
        return restore(seq)
    }

    /** 取全部仍在缓冲的记录（时间正序），供 UI 诊断 / 测试断言 */
    @Synchronized
    fun bufferedRecords(): List<Ft8QsoRecord> = entries.values.map { it.record }

    /** 是否仍在缓冲 [recordId] */
    @Synchronized
    fun isBuffered(recordId: Long): Boolean = entries.values.any { it.record.id == recordId }

    /** 清空缓冲并取消所有超时任务（页面销毁 / 测试清理用） */
    @Synchronized
    fun clear() {
        for (entry in entries.values) entry.expiryJob?.cancel()
        entries.clear()
    }

    /** 超时到期：移出缓冲并回调真正删除；条目已被撤销时静默返回 */
    private fun expire(seq: Long) {
        val entry: Entry?
        synchronized(this) {
            entry = entries.remove(seq)
        }
        if (entry != null) onExpire(entry.record)
    }
}
