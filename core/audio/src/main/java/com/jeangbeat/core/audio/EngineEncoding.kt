package com.jeangbeat.core.audio

import com.jeangbeat.core.model.AutomationTarget
import com.jeangbeat.core.model.Channel
import com.jeangbeat.core.model.Clip
import com.jeangbeat.core.model.Instrument
import com.jeangbeat.core.model.Note
import com.jeangbeat.core.model.Project
import com.jeangbeat.core.model.Song

/**
 * Converts model objects into the flat arrays the C++ engine expects.
 * Slot layouts must match the *_param namespaces in engine/Constants.h.
 */
internal object EngineEncoding {
    const val TYPE_DRUM = 0
    const val TYPE_BASS808 = 1
    const val TYPE_SYNTH = 2
    const val TYPE_SAMPLER = 3

    const val NOTE_FLAG_SLIDE = 1

    fun type(instrument: Instrument): Int = when (instrument) {
        is Instrument.Drum -> TYPE_DRUM
        is Instrument.Bass808 -> TYPE_BASS808
        is Instrument.Synth -> TYPE_SYNTH
        is Instrument.Sampler -> TYPE_SAMPLER
    }

    fun sound(instrument: Instrument): Int = (instrument as? Instrument.Drum)?.sound?.ordinal ?: 0

    fun sampleId(instrument: Instrument): Int = (instrument as? Instrument.Sampler)?.sampleId ?: -1

    fun params(instrument: Instrument): FloatArray = when (instrument) {
        is Instrument.Drum -> floatArrayOf(instrument.tune, instrument.decay, instrument.chokeGroup.toFloat())
        is Instrument.Sampler -> floatArrayOf(instrument.tune, instrument.decay, 0f, instrument.rootPitch.toFloat())
        is Instrument.Bass808 -> floatArrayOf(instrument.decay, instrument.glide, instrument.drive, instrument.punch, instrument.tune)
        is Instrument.Synth -> floatArrayOf(
            instrument.waveform.ordinal.toFloat(), instrument.cutoff, instrument.resonance, instrument.attack,
            instrument.decay, instrument.sustain, instrument.release, instrument.detune, instrument.envAmount,
        )
    }

    /** Ints [start, length, pitch, velocity, flags] and floats [pan, fineCents, release] per audible note. */
    fun notes(notes: List<Note>): Pair<IntArray, FloatArray> {
        val audible = notes.filterNot { it.muted }
        val ints = IntArray(audible.size * 5)
        val floats = FloatArray(audible.size * 3)
        audible.forEachIndexed { i, n ->
            ints[i * 5] = n.startTick
            ints[i * 5 + 1] = n.lengthTicks
            ints[i * 5 + 2] = n.pitch
            ints[i * 5 + 3] = n.velocity
            ints[i * 5 + 4] = if (n.slide) NOTE_FLAG_SLIDE else 0
            floats[i * 3] = n.pan
            floats[i * 3 + 1] = n.finePitch
            floats[i * 3 + 2] = n.release
        }
        return ints to floats
    }

    /** Maps channel ids to engine channel indices (project channel order). */
    fun channelIndex(channels: List<Channel>): Map<Int, Int> =
        channels.withIndex().associate { (index, channel) -> channel.id to index }

    /** Clips that should sound: clip not muted, track not muted, and track soloed if any track is. */
    fun audibleClips(song: Song): List<Clip> {
        val anySolo = song.tracks.any { it.solo }
        val tracks = song.tracks.associateBy { it.id }
        return song.clips.filter { c ->
            val t = tracks[c.trackId] ?: return@filter false
            !c.muted && !t.muted && (!anySolo || t.solo)
        }
    }

    /** Automation target encoding: type, a, b, c, min, max, log. Null if the target no longer exists. */
    data class Target(val type: Int, val a: Int, val b: Int, val c: Int, val min: Float, val max: Float, val log: Boolean)

    fun target(project: Project, target: AutomationTarget, channelIndex: Map<Int, Int>): Target? = when (target) {
        is AutomationTarget.MixerVolume -> Target(0, target.track, 0, 0, 0f, 1f, false)
        is AutomationTarget.MixerPan -> Target(1, target.track, 0, 0, -1f, 1f, false)
        is AutomationTarget.ChannelVolume -> channelIndex[target.channelId]?.let { Target(2, it, 0, 0, 0f, 1f, false) }
        is AutomationTarget.ChannelPan -> channelIndex[target.channelId]?.let { Target(3, it, 0, 0, -1f, 1f, false) }
        is AutomationTarget.EffectParam -> {
            val track = project.mixer.track(target.track)
            val position = track?.effects?.indexOfFirst { it.id == target.slotId } ?: -1
            val spec = track?.effects?.getOrNull(position)?.type?.params?.getOrNull(target.param)
            if (position < 0 || spec == null) null else Target(4, target.track, position, target.param, spec.min, spec.max, spec.log)
        }
    }
}
