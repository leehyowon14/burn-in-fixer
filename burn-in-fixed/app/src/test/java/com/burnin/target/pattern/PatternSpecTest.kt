package com.burnin.target.pattern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PatternSpecTest {

    @Test
    fun parsesGrayLevels() {
        // 255 * 0.70 = 178.5 → 179 (0xB3)
        assertEquals(0xFFB3B3B3.toInt(), PatternSpec.parse("gray70")!!.color)
        // 255 * 0.85 = 216.75 → 217 (0xD9)
        assertEquals(0xFFD9D9D9.toInt(), PatternSpec.parse("white85")!!.color)
        assertEquals(0xFF4D4D4D.toInt(), PatternSpec.parse("gray30")!!.color)
        assertEquals(PatternSpec.Kind.SOLID, PatternSpec.parse("gray70")!!.kind)
    }

    @Test
    fun parsesRgbChannels() {
        assertEquals(0xFFB30000.toInt(), PatternSpec.parse("red70")!!.color)
        assertEquals(0xFF00B300.toInt(), PatternSpec.parse("green70")!!.color)
        assertEquals(0xFF0000B3.toInt(), PatternSpec.parse("blue70")!!.color)
    }

    @Test
    fun parsesBlackAndSpecials() {
        assertEquals(PatternSpec.COLOR_BLACK, PatternSpec.parse("black")!!.color)
        assertEquals(PatternSpec.Kind.MARKER, PatternSpec.parse("marker")!!.kind)
        assertEquals(PatternSpec.Kind.CHECKER, PatternSpec.parse("checkerboard")!!.kind)
        assertEquals(PatternSpec.Kind.DOTGRID, PatternSpec.parse("dotgrid")!!.kind)
        assertEquals(PatternSpec.Kind.GRID, PatternSpec.parse("grid")!!.kind)
    }

    @Test
    fun clampsAndRejects() {
        // 100 초과 퍼센트는 100으로 clamp
        assertEquals(0xFFFFFFFF.toInt(), PatternSpec.parse("gray200")!!.color)
        assertNull(PatternSpec.parse("rainbow"))
        assertNull(PatternSpec.parse(""))
    }
}
