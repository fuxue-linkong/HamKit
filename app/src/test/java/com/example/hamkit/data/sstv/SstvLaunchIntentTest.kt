package com.example.hamkit.data.sstv

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 二期 T8：「过境提醒 → SSTV 接收态」Intent 契约的单元测试。
 *
 * 通知侧写、Activity 侧读，中间只隔着几个字符串 key。这类契约一旦写错不会崩溃，
 * 只会表现为「点通知没反应」，因此这里把读写两端放在一起断言。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SstvLaunchIntentTest {

    @Test
    fun `写入直达请求后可原样读出`() {
        val intent = SstvLaunchIntent.putLaunchRequest(Intent(), "PD_120")
        assertTrue(SstvLaunchIntent.isAutoStart(intent))
        assertEquals("PD_120", SstvLaunchIntent.presetModeName(intent))
    }

    @Test
    fun `无预置模式时仍要求直达接收态`() {
        val intent = SstvLaunchIntent.putLaunchRequest(Intent())
        assertTrue(SstvLaunchIntent.isAutoStart(intent))
        assertNull(SstvLaunchIntent.presetModeName(intent))
    }

    @Test
    fun `普通启动 Intent 不会被误判为直达请求`() {
        // 桌面图标启动、其他功能内部跳转都走这里，绝不能把用户拽进 SSTV 页
        assertFalse(SstvLaunchIntent.isAutoStart(Intent()))
        assertFalse(SstvLaunchIntent.isAutoStart(null))
        assertNull(SstvLaunchIntent.presetModeName(Intent()))
    }

    @Test
    fun `显式写入 false 时不视为直达`() {
        val intent = Intent().putExtra(SstvLaunchIntent.EXTRA_AUTO_START, false)
        assertFalse(SstvLaunchIntent.isAutoStart(intent))
    }
}
