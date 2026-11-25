package com.jeangbeat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelOpsTest {
    private val demo = DefaultProjects.demo("D").addPattern("P2", copyCurrent = true)

    @Test
    fun cloneCopiesNotesInEveryPatternAndSitsBelow() {
        val p = demo.cloneChannel(1, "Kick 2")
        val i = p.channels.indexOfFirst { it.id == 1 }
        val clone = p.channels[i + 1]
        assertEquals("Kick 2", clone.name)
        p.patterns.forEach { pat -> assertEquals(pat.notesFor(1), pat.notesFor(clone.id)) }
        assertEquals(demo.nextId + 1, p.nextId)
    }

    @Test
    fun clearEverywhereAndColour() {
        val p = demo.clearChannelEverywhere(1).setChannelColor(1, 13)
        assertTrue(p.patterns.all { it.notesFor(1).isEmpty() })
        assertEquals(13 % CHANNEL_COLOR_COUNT, p.channel(1)!!.color)
    }

    @Test
    fun fillEachSteps() {
        val p = DefaultProjects.empty("F").fillEachSteps(4, 2)
        assertEquals((0 until 16 step 2).toList(), p.currentPattern.channelSteps(4))
        assertSame(p, p.fillEachSteps(4, 0))
    }

    @Test
    fun moveUpDownClamps() {
        val p = demo.moveChannelBy(1, -1)
        assertEquals(1, p.channels.first().id)
        val down = demo.moveChannelBy(1, 1)
        assertEquals(1, down.channels[1].id)
        val last = demo.channels.last().id
        assertEquals(last, demo.moveChannelBy(last, 5).channels.last().id)
    }

    @Test
    fun replaceInstrumentKeepsNotes() {
        val p = demo.replaceInstrument(1, Instrument.Drum(DrumSound.TOM))
        assertEquals(DrumSound.TOM, (p.channel(1)!!.instrument as Instrument.Drum).sound)
        assertEquals(demo.currentPattern.notesFor(1), p.currentPattern.notesFor(1))
    }
}

class SplitByChannelTest {
    @Test
    fun splitMakesOnePatternPerChannelAndReplacesClips() {
        val demo = DefaultProjects.demo("D") // pattern 10 has kick, snare, clap, hat, chhing; clip 20 on track 1
        val p = demo.splitByChannel(10)
        val parts = p.patterns.filter { it.name.startsWith("Pattern 1 – ") }
        assertEquals(5, parts.size)
        parts.forEach { part -> assertEquals(1, part.notes.size) }
        assertTrue(p.song.clips.none { it is Clip.PatternClip && it.patternId == 10 })
        val newClips = p.song.clips.filterIsInstance<Clip.PatternClip>().filter { c -> parts.any { it.id == c.patternId } }
        assertEquals(5, newClips.size)
        assertEquals(5, newClips.map { it.trackId }.distinct().size)
        assertTrue(newClips.all { it.startTick == 0 && it.lengthTicks == 8 * Timing.TICKS_PER_BAR })
        assertEquals(parts.first().id, p.currentPatternId)
        assertTrue(p.pattern(10) != null)
    }

    @Test
    fun nothingToSplit() {
        val p = DefaultProjects.demo("D")
        assertSame(p, p.splitByChannel(11)) // 808 pattern has a single channel
    }
}
