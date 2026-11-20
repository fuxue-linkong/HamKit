package com.example.hamkit.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignTokensTest {

    @Test
    fun `radii are six distinct ordered values`() {
        val values = listOf(
            Radii.Badge, Radii.Control, Radii.Card,
            Radii.CardGroup, Radii.Sheet, Radii.Dock,
        )
        assertEquals(6, values.map { it.value }.toSet().size)
        assertTrue(values.zipWithNext().all { (a, b) -> a.value < b.value })
    }

    @Test
    fun `motion press uses bouncy spring`() {
        val spec = Motions.press<Float>()
        assertEquals(0.55f, spec.dampingRatio, 0.001f)
        assertEquals((Spring.StiffnessMedium * 2).toFloat(), spec.stiffness, 0.1f)
    }

    @Test
    fun `motion scale values`() {
        assertEquals(3, MotionScale.entries.size)
    }
}
