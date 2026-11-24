package com.jeangbeat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SongOpsTest {
    private val bar = Timing.TICKS_PER_BAR
    private val base = DefaultProjects.empty("Song")

    @Test
    fun patternClipDefaultsToPatternLength() {
        val p = base.addPatternClip(trackId = 2, startTick = bar, patternId = 10)
        val clip = p.song.clips.last() as Clip.PatternClip
        assertEquals(bar, clip.startTick)
        assertEquals(base.currentPattern.lengthTicks, clip.lengthTicks)
        assertEquals(2, clip.trackId)
    }

    @Test
    fun songLengthRoundsUpToBars() {
        val p = base.addPatternClip(1, 3 * bar, 10, lengthTicks = 100)
        assertEquals(4 * bar, p.song.lengthTicks)
        assertEquals(bar, Song(clips = emptyList()).lengthTicks)
    }

    @Test
    fun sliceSplitsClipAndKeepsPatternPosition() {
        val p0 = base.addPatternClip(1, 0, 10, lengthTicks = 4 * bar)
        val id = p0.song.clips.last().id
        val p = p0.sliceClip(id, bar)
        val parts = p.song.clips.filterIsInstance<Clip.PatternClip>().filter { it.startTick == 0 && it.lengthTicks == bar || it.startTick == bar }
        assertTrue(parts.any { it.startTick == bar && it.lengthTicks == 3 * bar && it.offsetTicks == bar })
        assertTrue(parts.any { it.id == id && it.lengthTicks == bar })
    }

    @Test
    fun sliceOutsideClipDoesNothing() {
        val id = base.song.clips.first().id
        assertEquals(base, base.sliceClip(id, 0))
        assertEquals(base, base.sliceClip(id, 10 * bar))
    }

    @Test
    fun makeUniqueCopiesPatternForThatClipOnly() {
        val p0 = base.addPatternClip(1, bar, 10)
        val clipId = p0.song.clips.last().id
        val p = p0.makeClipUnique(clipId, "Pattern 1 (2)")
        val clip = p.song.clip(clipId) as Clip.PatternClip
        assertNotEquals(10, clip.patternId)
        assertEquals("Pattern 1 (2)", p.pattern(clip.patternId)?.name)
        assertEquals(10, (p.song.clips.first() as Clip.PatternClip).patternId)
    }

    @Test
    fun deletingPatternRemovesItsClips() {
        val p = base.addPattern("P2", copyCurrent = false).let { it.addPatternClip(1, bar, it.currentPatternId) }
        val p2Id = p.currentPatternId
        val after = p.deletePattern(p2Id)
        assertTrue(after.song.clips.none { it is Clip.PatternClip && it.patternId == p2Id })
    }

    @Test
    fun moveResizeMuteDuplicate() {
        val id = base.song.clips.first().id
        val p = base.moveClip(id, 2 * bar, 3).resizeClip(id, 2 * bar).toggleClipMute(id).duplicateClip(id)
        val clip = p.song.clip(id)!!
        assertEquals(2 * bar, clip.startTick)
        assertEquals(3, clip.trackId)
        assertEquals(2 * bar, clip.lengthTicks)
        assertTrue(clip.muted)
        assertEquals(4 * bar, p.song.clips.last().startTick)
    }

    @Test
    fun automationInterpolatesAndSlices() {
        val p0 = base.addAutomationClip(1, 0, bar, AutomationTarget.MixerVolume(1))
        val id = p0.song.clips.last().id
        val p = p0.setAutomationPoints(id, listOf(AutoPoint(0, 0f), AutoPoint(bar, 1f)))
        val clip = p.song.clip(id) as Clip.AutomationClip
        assertEquals(0.5f, clip.valueAt(bar / 2), 0.001f)
        val sliced = p.sliceClip(id, bar / 4)
        val right = sliced.song.clips.last() as Clip.AutomationClip
        assertEquals(0.25f, right.points.first().value, 0.001f)
    }

    @Test
    fun loopRegion() {
        assertTrue(base.setLoop(bar, 3 * bar).song.hasLoop)
        assertFalse(base.setLoop(3 * bar, bar).song.hasLoop)
        assertFalse(base.setLoop(bar, 3 * bar).clearLoop().song.hasLoop)
    }

    @Test
    fun tracksCanBeAddedAndRemoved() {
        val p = base.addPlaylistTrack()
        assertEquals(Song.DEFAULT_TRACK_COUNT + 1, p.song.tracks.size)
        val removed = p.removePlaylistTrack(1)
        assertTrue(removed.song.clips.none { it.trackId == 1 })
    }
}
