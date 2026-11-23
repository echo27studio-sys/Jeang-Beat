package com.jeangbeat.core.audio

import com.jeangbeat.core.model.DrumSound
import com.jeangbeat.core.model.Instrument
import com.jeangbeat.core.model.Note
import com.jeangbeat.core.model.Waveform
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
    fun notesArePackedInOrder() {
        assertArrayEquals(
            intArrayOf(0, 24, 60, 100, 48, 12, 62, 90),
            EngineEncoding.notes(listOf(Note(0, 24, 60, 100), Note(48, 12, 62, 90))),
        )
    }
}
