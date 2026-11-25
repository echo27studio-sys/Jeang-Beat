package com.jeangbeat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RecordOpsTest {
    private val bar = Timing.TICKS_PER_BAR
    private val step = Timing.TICKS_PER_STEP

    @Test
    fun quantizesToTheNearestStep() {
        assertEquals(48, Recording.noteTick(50f, 0.0, 120f, step, 0, bar))
        assertEquals(72, Recording.noteTick(61f, 0.0, 120f, step, 0, bar))
    }

    @Test
    fun takesOffOutputLatency() {
        // 120 BPM = 192 ticks per second; 125 ms = 24 ticks = one step.
        assertEquals(48, Recording.noteTick(72f, 125.0, 120f, step, 0, bar))
    }

    @Test
    fun wrapsIntoTheLoop() {
        // Pressed just after the loop restarted, compensated back into the previous pass: end of the pattern.
        assertEquals(bar - step, Recording.noteTick(2f, 125.0, 120f, step, 0, bar))
        // Rounding up to the loop end goes to the loop start.
        assertEquals(0, Recording.noteTick(bar - 3f, 0.0, 120f, step, 0, bar))
        // Piano-roll loop region bar 2..3.
        assertEquals(bar + 24, Recording.noteTick(2 * bar + 24f, 0.0, 120f, step, bar, 2 * bar))
    }

    @Test
    fun recordNoteOverdubsWithoutDoubles() {
        val p0 = DefaultProjects.empty("T")
        val ch = p0.channels.first().id
        val p1 = p0.recordNote(ch, 60, 96, 24, 110)
        val n = p1.currentPattern.notesFor(ch).single { it.startTick == 96 }
        assertEquals(110, n.velocity)
        assertSame(p1, p1.recordNote(ch, 60, 96, 24))
        assertEquals(2, p1.recordNote(ch, 62, 96, 24).currentPattern.notesFor(ch).count { it.startTick == 96 })
    }
}
