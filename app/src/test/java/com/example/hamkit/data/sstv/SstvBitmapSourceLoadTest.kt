package com.example.hamkit.data.sstv

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * [SstvBitmapSource.loadOrError] 的端到端用例（Robolectric 提供 Bitmap/BitmapFactory）。
 *
 * 锁死「是否裁剪」的对外信号：只等比缩放、没有裁像素的原图必须是 `cropped = false`，
 * 否则发射页会对正确比例的图误报「已按 XX 比例自动裁剪原图」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SstvBitmapSourceLoadTest {

    private fun png(width: Int, height: Int): File {
        val file = File.createTempFile("sstv-src", ".png")
        file.deleteOnExit()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        bitmap.recycle()
        return file
    }

    @Test
    fun `等比缩放的原图不标记裁剪`() {
        val loaded = SstvBitmapSource.loadOrError(png(640, 480), SstvMode.ROBOT_36).getOrThrow()
        assertFalse("640×480 与 Robot 36 同比例，不应提示裁剪", loaded.cropped)
        assertEquals(320, loaded.source.width)
        assertEquals(240, loaded.source.height)
        assertEquals(640, loaded.originalWidth)
        assertEquals(480, loaded.originalHeight)
    }

    @Test
    fun `比例不同的原图标记裁剪`() {
        val loaded = SstvBitmapSource.loadOrError(png(640, 360), SstvMode.ROBOT_36).getOrThrow()
        assertTrue("640×360（16:9）发 4:3 模式应标记裁剪", loaded.cropped)
        assertEquals(320, loaded.source.width)
        assertEquals(240, loaded.source.height)
    }
}