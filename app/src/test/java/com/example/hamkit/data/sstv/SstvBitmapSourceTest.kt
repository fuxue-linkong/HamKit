package com.example.hamkit.data.sstv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发射源自动裁剪判定的纯 JVM 用例。
 *
 * 回归背景：UI 曾用「裁剪后的像素尺寸 vs 原图尺寸」判断是否裁过，
 * 于是 1280×992 → 640×496 这种**只等比缩放、没裁任何像素**的图，
 * 也会提示「已按 XX 比例自动裁剪原图 1280×992」。
 * 正确判据是宽高比：等价于 `源宽 × 目标高 == 源高 × 目标宽`。
 */
class SstvBitmapSourceTest {

    @Test
    fun `同比例的缩放不算裁剪`() {
        assertFalse(SstvBitmapSource.needsAspectCrop(1280, 992, 640, 496))
        assertFalse(SstvBitmapSource.needsAspectCrop(1920, 1488, 640, 496))
        assertFalse(SstvBitmapSource.needsAspectCrop(640, 496, 640, 496))
        assertFalse(SstvBitmapSource.needsAspectCrop(320, 248, 640, 496))
    }

    @Test
    fun `比例不同才需要裁剪`() {
        assertTrue("16:9 照片发 PD-120 需要裁上下", SstvBitmapSource.needsAspectCrop(1920, 1080, 640, 496))
        assertTrue("竖拍需要裁左右", SstvBitmapSource.needsAspectCrop(1080, 1920, 640, 496))
        assertTrue("4:3 与 PD-120 不同比例", SstvBitmapSource.needsAspectCrop(4000, 3000, 640, 496))
        assertTrue("只差一个像素也应按比例裁", SstvBitmapSource.needsAspectCrop(641, 496, 640, 496))
    }

    @Test
    fun `裁剪目标比例跟随各个 SSTV 模式`() {
        // Robot 36：320×240（4:3）
        assertFalse(SstvBitmapSource.needsAspectCrop(1600, 1200, 320, 240))
        assertTrue(SstvBitmapSource.needsAspectCrop(1600, 900, 320, 240))
        // PD-290：800×616
        assertFalse(SstvBitmapSource.needsAspectCrop(1600, 1232, 800, 616))
        assertTrue(SstvBitmapSource.needsAspectCrop(1600, 1200, 800, 616))
    }
}