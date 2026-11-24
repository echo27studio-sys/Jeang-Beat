package com.jeangbeat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteOpsTest {
    private val n1 = Note(5, 20, 60)
    private val n2 = Note(50, 10, 64)

    @Test
    fun quantizeFullAndHalfStrength() {
        assertEquals(listOf(0, 48), listOf(n1, n2).quantized(24).map { it.startTick })
        assertEquals(listOf(3, 49), listOf(n1, n2).quantized(24, strength = 0.5f).map { it.startTick })
        assertEquals(listOf(0, 50), listOf(n1, n2).quantized(24, selection = setOf(n1.key)).map { it.startTick })
    }

    @Test
    fun transposeClamps() {
        assertEquals(listOf(72, 76), listOf(n1, n2).transposed(12).map { it.pitch })
        assertEquals(127, listOf(Note(0, 1, 120)).transposed(24).single().pitch)
    }

    @Test
    fun duplicatePlacesCopyAfterSelection() {
        val (notes, copies) = listOf(Note(0, 24, 60), Note(24, 24, 62)).duplicated(emptySet(), grid = 24, lengthTicks = 384)
        assertEquals(4, notes.size)
        assertEquals(setOf(NoteKey(48, 60), NoteKey(72, 62)), copies)
    }

    @Test
    fun legatoStretchesToNextStart() {
        val out = listOf(Note(0, 10, 60), Note(48, 10, 62)).legato(lengthTicks = 96)
        assertEquals(listOf(48, 48), out.map { it.lengthTicks })
    }

    @Test
    fun sliceSplitsNote() {
        val out = listOf(Note(0, 48, 60)).sliced(Note(0, 48, 60), 24)
        assertEquals(setOf(Note(0, 24, 60), Note(24, 24, 60)), out.toSet())
    }

    @Test
    fun chordStamp() {
        assertEquals(listOf(60, 63, 67, 70), chordNotes(60, ChordType.MINOR7, 0, 96).map { it.pitch })
    }

    @Test
    fun euclideanRhythms() {
        assertEquals(listOf(0, 3, 6), euclid(3, 8))          // tresillo
        assertEquals(listOf(0, 2, 4, 5, 7), euclid(5, 8))    // cinquillo family
        assertEquals(listOf(1, 4, 7), euclid(3, 8, rotation = 1))
        assertEquals(emptyList<Int>(), euclid(0, 16))
        assertEquals((0 until 4).toList(), euclid(9, 4))
    }

    @Test
    fun ringEditing() {
        val base = DefaultProjects.empty("R")
        val p = base.setChannelSteps(6, listOf(0, 3, 6, 10, 12))
        assertEquals(listOf(0, 3, 6, 10, 12), p.currentPattern.channelSteps(6))
        val rotated = p.rotateChannel(6, 2)
        assertEquals(listOf(2, 5, 8, 12, 14), rotated.currentPattern.channelSteps(6))
        val wrapped = p.rotateChannel(6, -1)
        assertEquals(listOf(2, 5, 9, 11, 15), wrapped.currentPattern.channelSteps(6))
    }

    @Test
    fun twelveEightMeter() {
        val p = DefaultProjects.empty("T").setPatternMeter(Meter.TWELVE_EIGHT)
        val pattern = p.currentPattern
        assertEquals(12, pattern.lengthSteps)
        assertEquals(Timing.TICKS_PER_BAR, pattern.lengthTicks)
        val withStep = p.toggleStep(1, 11)
        assertEquals(11 * 32, withStep.currentPattern.notesFor(1).single().startTick)
        assertEquals(24, withStep.setPatternBars(2).currentPattern.lengthSteps)
    }

    @Test
    fun descriptionListsDrumHits() {
        val p = DefaultProjects.demo("D")
        assertTrue(p.describePattern().startsWith("Kick on 1, 8, 11"))
    }

    @Test
    fun migrationFromVersion1() {
        val v1 = DefaultProjects.empty("Old").let { p ->
            p.copy(formatVersion = 1, channels = p.channels.map { it.copy(mixerTrack = 0) }, song = Song())
        }
        val v2 = DefaultProjects.migrate(v1)
        assertEquals(Project.CURRENT_FORMAT_VERSION, v2.formatVersion)
        assertEquals((1..v1.channels.size).toList(), v2.channels.map { it.mixerTrack })
        assertEquals("Kick", v2.mixer.track(1)!!.name)
        assertEquals(1, v2.song.clips.size)
    }
}
