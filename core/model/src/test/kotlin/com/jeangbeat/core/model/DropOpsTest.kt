package com.jeangbeat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DropOpsTest {
    private val base = DefaultProjects.empty("Test", "Pattern 1")
    private val kick = SampleRef(0, "Kick 808", "h_abc.wav", frames = 48_000, sampleRate = 48_000, channels = 1)

    @Test
    fun dropOnRackAddsSamplerChannel() {
        val p = base.dropItem(DropItem.Sample(kick), DropTarget.NewChannel)
        assertEquals(base.channels.size + 1, p.channels.size)
        val ch = p.channels.last()
        assertEquals("Kick 808", ch.name)
        val sampler = ch.instrument as Instrument.Sampler
        assertEquals("h_abc.wav", p.sample(sampler.sampleId)!!.fileName)
    }

    @Test
    fun sameFileIsSharedNotDuplicated() {
        val p = base.dropItem(DropItem.Sample(kick), DropTarget.NewChannel)
            .dropItem(DropItem.Sample(kick), DropTarget.NewChannel)
        assertEquals(1, p.samples.size)
        val ids = p.channels.takeLast(2).map { (it.instrument as Instrument.Sampler).sampleId }
        assertEquals(ids[0], ids[1])
        assertEquals(p.samples.single().id, base.dropItem(DropItem.Sample(kick), DropTarget.NewChannel).sampleIdFor(kick))
    }

    @Test
    fun sampleIdForMatchesTheEdit() {
        val id = base.sampleIdFor(kick)
        val p = base.dropItem(DropItem.Sample(kick), DropTarget.NewChannel)
        assertEquals(id, (p.channels.last().instrument as Instrument.Sampler).sampleId)
    }

    @Test
    fun dropOnChannelReplacesSoundKeepsNotes() {
        val target = base.channels.first()
        val withNotes = base.toggleStep(target.id, 0)
        val p = withNotes.dropItem(DropItem.Sample(kick), DropTarget.Channel(target.id))
        val ch = p.channel(target.id)!!
        assertTrue(ch.instrument is Instrument.Sampler)
        assertEquals("Kick 808", ch.name)
        assertEquals(withNotes.currentPattern.notesFor(target.id), p.currentPattern.notesFor(target.id))
        assertEquals(withNotes.channels.size, p.channels.size)
    }

    @Test
    fun dropOnSamplerKeepsTuning() {
        val p0 = base.dropItem(DropItem.Sample(kick), DropTarget.NewChannel)
        val ch = p0.channels.last()
        val tuned = p0.replaceInstrument(ch.id, (ch.instrument as Instrument.Sampler).copy(tune = 5f))
        val snare = kick.copy(name = "Snare", fileName = "h_def.wav")
        val p = tuned.dropItem(DropItem.Sample(snare), DropTarget.Channel(ch.id))
        val s = p.channel(ch.id)!!.instrument as Instrument.Sampler
        assertEquals(5f, s.tune)
        assertEquals("h_def.wav", p.sample(s.sampleId)!!.fileName)
    }

    @Test
    fun dropOnPlaylistAddsAudioClip() {
        val track = base.song.tracks[1]
        val p = base.dropItem(DropItem.Sample(kick), DropTarget.PlaylistLane(track.id, 384))
        val clip = p.song.clips.filterIsInstance<Clip.AudioClip>().single()
        assertEquals(track.id, clip.trackId)
        assertEquals(384, clip.startTick)
        // One second at the project tempo.
        assertEquals((base.tempo / 60.0 * Timing.TICKS_PER_BEAT).toInt(), clip.lengthTicks)
    }

    @Test
    fun dropBelowLastTrackAddsTrack() {
        val p = base.dropItem(DropItem.Sample(kick), DropTarget.PlaylistLane(-1, 0))
        assertEquals(base.song.tracks.size + 1, p.song.tracks.size)
        assertEquals(p.song.tracks.last().id, p.song.clips.filterIsInstance<Clip.AudioClip>().single().trackId)
    }

    @Test
    fun soundOnPlaylistIsIgnored() {
        val sound = DropItem.Sound("Clap", Instrument.Drum(DrumSound.CLAP))
        assertSame(base, base.dropItem(sound, DropTarget.PlaylistLane(base.song.tracks[0].id, 0)))
    }

    @Test
    fun dropOnMixerRoutesNewChannel() {
        val p = base.dropItem(DropItem.Sound("Clap", Instrument.Drum(DrumSound.CLAP)), DropTarget.MixerInsert(7))
        assertEquals(7, p.channels.last().mixerTrack)
    }

    @Test
    fun fullRackIsUnchanged() {
        var full = base
        while (full.channels.size < MAX_CHANNELS) full = full.addChannel("x", Instrument.Drum(DrumSound.KICK))
        assertSame(full, full.dropItem(DropItem.Sample(kick), DropTarget.NewChannel))
    }
}
