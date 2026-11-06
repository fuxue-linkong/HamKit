package com.example.hamkit.data.reminder

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowAlarmManager
import kotlin.math.abs

/**
 * [ReminderScheduler] 的排期测试。
 *
 * 重点回归一个真实故障：手环闹钟曾经挂在「手机通知的提前量是否已过」之后，
 * 于是「卫星 5 分钟后过境 → 我打开提醒」这种最常见的用法下，手环闹钟根本没注册，
 * 用户看到的就是"到点什么都不弹"。
 */
@RunWith(RobolectricTestRunner::class)
class ReminderSchedulerTest {

    private lateinit var context: Context
    private lateinit var scheduler: ReminderScheduler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ShadowAlarmManager.reset()
        scheduler = ReminderScheduler(context)
    }

    private fun item(aosOffsetMs: Long): ReminderItem {
        val aos = System.currentTimeMillis() + aosOffsetMs
        return ReminderItem(
            catalogNumber = 25544,
            name = "ISS",
            aosTimeMillis = aos,
            losTimeMillis = aos + 600_000L,
            maxElevation = 42.0,
            aosAzimuth = 100,
            losAzimuth = 250,
            modes = listOf("FM"),
            enabled = true
        )
    }

    private fun scheduledTriggers(): List<Long> {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return Shadows.shadowOf(alarmManager).scheduledAlarms.map { it.triggerAtMs }
    }

    @Test
    fun `wearable alarm is still scheduled when phone lead time already passed`() {
        // AOS 只剩 5 分钟，而手机通知提前量是 10 分钟 → 手机侧只能补发；
        // 手环闹钟（AOS-60s）在 4 分钟后，必须照常注册。
        scheduler.schedule(item(5 * 60_000L), ReminderSettings(leadMinutes = 10, wearableEnabled = true))

        val triggers = scheduledTriggers()
        assertEquals("应同时排上手机补发与手环闹钟", 2, triggers.size)

        val expectedWearable = System.currentTimeMillis() + 5 * 60_000L - 60_000L
        assertTrue(
            "手环闹钟应在 AOS 前 60 秒，实际 $triggers",
            triggers.any { abs(it - expectedWearable) < 5_000L }
        )
    }

    @Test
    fun `no alarm at all when the pass already ended`() {
        scheduler.schedule(item(-5 * 60_000L), ReminderSettings(leadMinutes = 10, wearableEnabled = true))

        assertTrue("已过境的提醒不应再排任何闹钟", scheduledTriggers().isEmpty())
    }

    @Test
    fun `disabling wearable keeps the phone reminder`() {
        scheduler.schedule(item(5 * 60_000L), ReminderSettings(leadMinutes = 10, wearableEnabled = false))

        // 只剩手机侧那条补发闹钟；手环链路的闹钟被取消
        assertEquals(1, scheduledTriggers().size)
    }

    @Test
    fun `both sides fire immediately inside the final minute`() {
        // AOS 只剩 30 秒：手机提前量与手环提前量都已过，但卫星还没升起来 → 立刻补发
        scheduler.schedule(item(30_000L), ReminderSettings(leadMinutes = 10, wearableEnabled = true))

        val triggers = scheduledTriggers()
        assertEquals(2, triggers.size)

        val now = System.currentTimeMillis()
        assertTrue(
            "两条都应在 1 秒后立即触发，实际 $triggers",
            triggers.all { it in now..(now + 3_000L) }
        )
    }
}
