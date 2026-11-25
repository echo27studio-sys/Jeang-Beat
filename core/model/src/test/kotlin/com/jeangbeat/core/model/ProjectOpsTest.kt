package com.jeangbeat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectOpsTest {
    private val base = DefaultProjects.empty("Test")
    private val kick = 1
    private val bass = 8

    @Test
    fun toggleStepAddsAndRemovesNote() {
        val on = base.toggleStep(kick, 4)
        assertTrue(on.currentPattern.isStepOn(kick, 4))
        assertEquals(Note(96, 24, ROOT_PITCH, 100), on.currentPattern.notesFor(kick).single())

        val off = on.toggleStep(kick, 4)
        assertFalse(off.currentPattern.isStepOn(kick, 4))
        assertTrue(off.currentPattern.notes.isEmpty())
    }

    @Test
    fun toggleStepUsesInstrumentDefaultPitch() {
        val p = base.toggleStep(bass, 0)
        assertEquals(36, p.currentPattern.notesFor(bass).single().pitch)
    }

    @Test
    fun toggleStepOutsidePatternIsIgnored() {
        // AUTO patterns grow instead (see AutoLengthTest); a FIXED pattern ignores steps past its end.
        val fixed = base.setPatternBars(1)
        assertSame(fixed, fixed.toggleStep(kick, 16))
        assertSame(base, base.toggleStep(kick, -1))
        assertSame(base, base.toggleStep(999, 0))
    }

    @Test
    fun stepVelocityCanBeChangedAndIsClamped() {
        val p = base.toggleStep(kick, 2).setStepVelocity(kick, 2, 300)
        assertEquals(127, p.currentPattern.stepVelocity(kick, 2))
    }

    @Test
    fun addNoteClampsToPatternAndReplacesDuplicates() {
        val p = base
            .addNote(bass, Note(370, 100, 200, 100))
            .addNote(bass, Note(370, 5, 127, 90))
        val note = p.currentPattern.notesFor(bass).single()
        assertEquals(370, note.startTick)
        assertEquals(5, note.lengthTicks)
        assertEquals(127, note.pitch)
        assertEquals(90, note.velocity)
    }

    @Test
    fun replaceNoteMovesAndResizes() {
        val original = Note(0, 24, 36)
        val moved = Note(48, 72, 38)
        val p = base.addNote(bass, original).replaceNote(bass, original, moved)
        assertEquals(listOf(moved), p.currentPattern.notesFor(bass))
    }

    @Test
    fun shrinkingPatternDropsAndTrimsNotes() {
        val p = base
            .setPatternLength(32)
            .addNote(bass, Note(0, 32 * 24, 36))
            .addNote(bass, Note(20 * 24, 24, 36))
            .setPatternLength(16)
        val notes = p.currentPattern.notesFor(bass)
        assertEquals(1, notes.size)
        assertEquals(16 * 24, notes.single().lengthTicks)
    }

    @Test
    fun recordHitQuantizesAndWraps() {
        val p = base.recordHit(kick, tick = 50, pitch = 60, velocity = 110, gridTicks = 24)
        assertEquals(48, p.currentPattern.notesFor(kick).single().startTick)
        val wrapped = base.recordHit(kick, tick = 380, pitch = 60, velocity = 110, gridTicks = 24)
        assertEquals(0, wrapped.currentPattern.notesFor(kick).single().startTick)
    }

    @Test
    fun patternsCanBeAddedCopiedSelectedAndDeleted() {
        val withNote = base.toggleStep(kick, 0)
        val copied = withNote.addPattern("Pattern 2", copyCurrent = true)
        assertEquals(2, copied.patterns.size)
        assertEquals("Pattern 2", copied.currentPattern.name)
        assertTrue(copied.currentPattern.isStepOn(kick, 0))

        val blank = copied.addPattern("Pattern 3", copyCurrent = false)
        assertTrue(blank.currentPattern.notes.isEmpty())

        val deleted = blank.deletePattern(blank.currentPatternId)
        assertEquals(2, deleted.patterns.size)
        assertEquals(copied.currentPatternId, deleted.currentPatternId)

        val single = DefaultProjects.empty("x")
        assertSame(single, single.deletePattern(single.currentPatternId))
    }

    @Test
    fun removingChannelRemovesItsNotesEverywhere() {
        val p = base.toggleStep(kick, 0).addPattern("P2", copyCurrent = true).removeChannel(kick)
        assertTrue(p.channels.none { it.id == kick })
        assertTrue(p.patterns.all { kick !in it.notes })
    }

    @Test
    fun addChannelUsesFreshIds() {
        val p = base.addChannel("Tom", Instrument.Drum(DrumSound.TOM)).addChannel("Lead", Instrument.Synth())
        val ids = p.channels.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(base.nextId + 2, p.nextId)
    }

    @Test
    fun tempoAndSwingAreClamped() {
        assertEquals(Timing.MAX_TEMPO, base.withTempo(999f).tempo)
        assertEquals(Timing.MIN_TEMPO, base.withTempo(1f).tempo)
        assertEquals(1f, base.withSwing(3f).swing)
    }

    @Test
    fun demoProjectIsValid() {
        val demo = DefaultProjects.demo("Demo")
        val channelIds = demo.channels.map { it.id }.toSet()
        assertTrue(demo.currentPattern.notes.keys.all { it in channelIds })
        assertTrue(demo.currentPattern.notes.values.flatten().all { it.endTick <= demo.currentPattern.lengthTicks })
        assertTrue(demo.nextId > channelIds.max())
    }
}
