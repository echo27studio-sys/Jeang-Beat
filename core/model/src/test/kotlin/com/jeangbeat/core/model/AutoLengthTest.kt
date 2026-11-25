package com.jeangbeat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoLengthTest {
    private val bar = Timing.TICKS_PER_BAR
    private val base = DefaultProjects.empty("A") // pattern 10: AUTO, 1 bar minimum

    @Test
    fun drawingPastTheEndExtendsThePattern() {
        val p = base.addNote(8, Note(5 * bar + 10, 96, 36))
        assertEquals(6 * bar, p.currentPattern.lengthTicks)
        assertEquals(6, p.currentPattern.bars)
        assertEquals(96, p.currentPattern.stepCount)
    }

    @Test
    fun deletingShrinksBackToTheMinimum() {
        val n = Note(5 * bar, 96, 36)
        val p = base.addNote(8, n).removeNote(8, n)
        assertEquals(bar, p.currentPattern.lengthTicks)
    }

    @Test
    fun movingAndPastingExtend() {
        val n = Note(0, 96, 36)
        val moved = base.addNote(8, n).replaceNote(8, n, n.copy(startTick = 3 * bar))
        assertEquals(4 * bar, moved.currentPattern.lengthTicks)
        val pasted = base.editNotes(8) { it + (0 until 8).map { i -> Note(i * bar, 24, 36) } }
        assertEquals(8 * bar, pasted.currentPattern.lengthTicks)
    }

    @Test
    fun stepsPastTheEndGrowAnAutoPattern() {
        val p = base.toggleStep(1, 20) // step 21 is in bar 2
        assertEquals(2 * bar, p.currentPattern.lengthTicks)
    }

    @Test
    fun fixedLengthClampsAndAutoReturns() {
        val fixed = base.setPatternBars(2)
        assertEquals(LengthMode.FIXED, fixed.currentPattern.lengthMode)
        val clamped = fixed.addNote(8, Note(5 * bar, 96, 36))
        assertTrue(clamped.currentPattern.notesFor(8).single().endTick <= 2 * bar)
        assertEquals(2 * bar, clamped.currentPattern.lengthTicks)
        val auto = fixed.setPatternAutoLength()
        assertEquals(LengthMode.AUTO, auto.currentPattern.lengthMode)
        assertEquals(bar, auto.currentPattern.lengthTicks)
        assertEquals(512 * bar, base.setPatternBars(9999).currentPattern.lengthTicks)
    }

    @Test
    fun clipsFollowAutoPatternsUntilResizedByHand() {
        val grown = base.addNote(8, Note(3 * bar, 96, 36)).syncClipLengths()
        assertEquals(4 * bar, grown.song.clips.single().lengthTicks)
        val clipId = grown.song.clips.single().id
        val resized = grown.resizeClip(clipId, bar)
        assertFalse((resized.song.clip(clipId) as Clip.PatternClip).followsPattern)
        assertEquals(bar, resized.addNote(8, Note(6 * bar, 24, 36)).syncClipLengths().song.clip(clipId)!!.lengthTicks)
    }

    @Test
    fun demoKeepsItsLongClips() {
        val demo = DefaultProjects.demo("D").syncClipLengths()
        assertEquals(8 * bar, demo.song.clips.first().lengthTicks)
    }

    @Test
    fun migrationFromVersion2KeepsFixedLengths() {
        val v2 = DefaultProjects.demo("Old").let { p ->
            p.copy(
                formatVersion = 2,
                patterns = p.patterns.map { it.copy(lengthMode = LengthMode.AUTO) },
                song = p.song.copy(clips = p.song.clips.map { (it as Clip.PatternClip).copy(followsPattern = true) }),
            )
        }
        val v3 = DefaultProjects.migrate(v2)
        assertEquals(3, v3.formatVersion)
        assertTrue(v3.patterns.all { it.lengthMode == LengthMode.FIXED })
        assertTrue(v3.song.clips.all { !(it as Clip.PatternClip).followsPattern })
        assertEquals(8 * bar, v3.syncClipLengths().song.clips.first().lengthTicks)
    }
}
