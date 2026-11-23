package com.jeangbeat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScaleTest {
    @Test
    fun cMinorContainsTheRightNotes() {
        val inScale = (60..71).filter { Scale.MINOR.contains(it, root = 0) }.map { pitchName(it) }
        assertEquals(listOf("C4", "D4", "D#4", "F4", "G4", "G#4", "A#4"), inScale)
    }

    @Test
    fun snapGoesToNearestScaleNote() {
        // E (64) is not in C minor; D# (63) and F (65) are equally close, tie goes down.
        assertEquals(63, Scale.MINOR.snap(64, 0))
        assertEquals(60, Scale.MINOR.snap(60, 0))
    }

    @Test
    fun rootTransposesTheScale() {
        assertTrue(Scale.MAJOR.contains(66, root = 2)) // F# in D major
        assertFalse(Scale.MAJOR.contains(65, root = 2)) // F not in D major
    }

    @Test
    fun negativeIntervalsWork() {
        assertTrue(Scale.MINOR.contains(-12, root = 0))
    }

    @Test
    fun pitchNamesAndBlackKeys() {
        assertEquals("C4", pitchName(60))
        assertEquals("A4", pitchName(69))
        assertEquals("C-1", pitchName(0))
        assertTrue(isBlackKey(61))
        assertFalse(isBlackKey(64))
    }

    @Test
    fun lockOnlyRestrictsWhenEnabled() {
        val unlocked = ScaleSetting(root = 0, scale = Scale.MAJOR, lock = false)
        assertTrue(unlocked.allows(61))
        assertFalse(unlocked.copy(lock = true).allows(61))
    }

    @Test
    fun quantizeRoundsToNearestGrid() {
        assertEquals(0, Timing.quantize(11, 24))
        assertEquals(24, Timing.quantize(12, 24))
        assertEquals(0, Timing.quantize(-5, 24))
    }
}
