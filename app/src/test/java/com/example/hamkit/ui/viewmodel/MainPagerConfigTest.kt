package com.example.hamkit.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [MainPagerConfig] 单元测试。
 */
class MainPagerConfigTest {

    @Test
    fun `page count is 3`() {
        assertEquals(3, MainPagerConfig.PAGE_COUNT)
    }

    @Test
    fun `last page index is 2`() {
        assertEquals(2, MainPagerConfig.LAST_PAGE_INDEX)
    }

    @Test
    fun `coercePage clamps negative to 0`() {
        assertEquals(0, MainPagerConfig.coercePage(-1))
        assertEquals(0, MainPagerConfig.coercePage(-100))
    }

    @Test
    fun `coercePage clamps above max to last index`() {
        assertEquals(2, MainPagerConfig.coercePage(3))
        assertEquals(2, MainPagerConfig.coercePage(100))
    }

    @Test
    fun `coercePage keeps valid pages unchanged`() {
        assertEquals(0, MainPagerConfig.coercePage(0))
        assertEquals(1, MainPagerConfig.coercePage(1))
        assertEquals(2, MainPagerConfig.coercePage(2))
    }
}
