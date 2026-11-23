package com.jeangbeat.core.audio

import com.jeangbeat.core.model.Channel
import com.jeangbeat.core.model.Instrument
import com.jeangbeat.core.model.Note

/**
 * Converts model objects into the flat arrays the C++ engine expects.
 * Slot layouts must match the *_param namespaces in engine/Constants.h.
 */
internal object EngineEncoding {
    const val TYPE_DRUM = 0
    const val TYPE_BASS808 = 1
    const val TYPE_SYNTH = 2

    fun type(instrument: Instrument): Int = when (instrument) {
        is Instrument.Drum -> TYPE_DRUM
        is Instrument.Bass808 -> TYPE_BASS808
        is Instrument.Synth -> TYPE_SYNTH
    }

    fun sound(instrument: Instrument): Int = (instrument as? Instrument.Drum)?.sound?.ordinal ?: 0

    fun params(instrument: Instrument): FloatArray = when (instrument) {
        is Instrument.Drum -> floatArrayOf(
            instrument.tune,
            instrument.decay,
            instrument.chokeGroup.toFloat(),
        )
        is Instrument.Bass808 -> floatArrayOf(
            instrument.decay,
            instrument.glide,
            instrument.drive,
            instrument.punch,
            instrument.tune,
        )
        is Instrument.Synth -> floatArrayOf(
            instrument.waveform.ordinal.toFloat(),
            instrument.cutoff,
            instrument.resonance,
            instrument.attack,
            instrument.decay,
            instrument.sustain,
            instrument.release,
            instrument.detune,
            instrument.envAmount,
        )
    }

    /** [start, length, pitch, velocity] per note. */
    fun notes(notes: List<Note>): IntArray {
        val out = IntArray(notes.size * 4)
        notes.forEachIndexed { i, n ->
            out[i * 4] = n.startTick
            out[i * 4 + 1] = n.lengthTicks
            out[i * 4 + 2] = n.pitch
            out[i * 4 + 3] = n.velocity
        }
        return out
    }

    /** Maps channel ids to engine channel indices (project channel order). */
    fun channelIndex(channels: List<Channel>): Map<Int, Int> =
        channels.withIndex().associate { (index, channel) -> channel.id to index }
}
