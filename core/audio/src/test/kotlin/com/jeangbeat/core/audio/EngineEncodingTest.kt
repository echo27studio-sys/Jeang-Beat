package com.jeangbeat.core.audio

import com.jeangbeat.core.model.AutomationTarget
import com.jeangbeat.core.model.DefaultProjects
import com.jeangbeat.core.model.DrumSound
import com.jeangbeat.core.model.EffectType
import com.jeangbeat.core.model.addEffect
import com.jeangbeat.core.model.updatePlaylistTrack
import com.jeangbeat.core.model.Instrument
import com.jeangbeat.core.model.Note
import com.jeangbeat.core.model.Waveform
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineEncodingTest {
    @Test
    fun drumSoundIdsMatchNativeEnumOrder() {
        // Must match DrumSoundId in engine/DrumKit.h.
        val expected = listOf(
            "KICK", "SNARE", "CLAP", "HAT_CLOSED", "HAT_OPEN", "RIM",
            "TOM", "CRASH", "PERC", "SHAKER", "CHHING", "SKOR",
        )
        assertEquals(expected, DrumSound.entries.map { it.name })
    }

    @Test
    fun paramSlotsMatchNativeLayout() {
        assertArrayEquals(
            floatArrayOf(2f, 0.5f, 1f),
            EngineEncoding.params(Instrument.Drum(DrumSound.HAT_OPEN, tune = 2f, decay = 0.5f)),
            0f,
        )
        assertArrayEquals(
            floatArrayOf(1f, 0.1f, 0.2f, 0.3f, -2f),
            EngineEncoding.params(Instrument.Bass808(decay = 1f, glide = 0.1f, drive = 0.2f, punch = 0.3f, tune = -2f)),
            0f,
        )
        val synth = EngineEncoding.params(Instrument.Synth(waveform = Waveform.TRIANGLE))
        assertEquals(9, synth.size)
        assertEquals(2f, synth[0])
    }

    @Test
    fun notesArePackedWithPropertiesAndMutedNotesSkipped() {
        val (ints, floats) = EngineEncoding.notes(
            listOf(
                Note(0, 24, 60, 100, pan = -0.5f, finePitch = 20f, release = 0.8f),
                Note(24, 24, 61, 100, muted = true),
                Note(48, 12, 62, 90, slide = true),
            ),
        )
        assertArrayEquals(intArrayOf(0, 24, 60, 100, 0, 48, 12, 62, 90, 1), ints)
        assertArrayEquals(floatArrayOf(-0.5f, 20f, 0.8f, 0f, 0f, 0.5f), floats, 0f)
    }

    @Test
    fun samplerSendsRootPitch() {
        assertArrayEquals(floatArrayOf(2f, 1f, 0f, 48f), EngineEncoding.params(Instrument.Sampler(7, rootPitch = 48, tune = 2f)), 0f)
        assertEquals(7, EngineEncoding.sampleId(Instrument.Sampler(7)))
    }

    @Test
    fun trackMuteAndSoloDecideAudibleClips() {
        val base = DefaultProjects.demo("D")
        assertEquals(base.song.clips.size, EngineEncoding.audibleClips(base.song).size)
        val soloed = base.updatePlaylistTrack(2) { it.copy(solo = true) }
        assertEquals(listOf(2), EngineEncoding.audibleClips(soloed.song).map { it.trackId }.distinct())
        val muted = base.updatePlaylistTrack(1) { it.copy(muted = true) }
        assertTrue(EngineEncoding.audibleClips(muted.song).none { it.trackId == 1 })
    }

    @Test
    fun effectAutomationTargetsSlotPosition() {
        val p = DefaultProjects.demo("D").addEffect(4, EffectType.FILTER)
        val slot = p.mixer.track(4)!!.effects.last()
        val t = EngineEncoding.target(p, AutomationTarget.EffectParam(4, slot.id, 1), emptyMap())!!
        assertEquals(4, t.type)
        assertEquals(1, t.b) // second slot on insert 4 (after the compressor)
        assertTrue(t.log)
        assertEquals(null, EngineEncoding.target(p, AutomationTarget.EffectParam(4, 999, 1), emptyMap()))
    }
}
