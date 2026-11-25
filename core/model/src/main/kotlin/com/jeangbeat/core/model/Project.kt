package com.jeangbeat.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Note(
    val startTick: Int,
    val lengthTicks: Int,
    /** MIDI pitch 0..127. */
    val pitch: Int,
    /** 1..127. */
    val velocity: Int = DEFAULT_VELOCITY,
    /** Per-note pan, -1 (left) .. 1 (right). */
    val pan: Float = 0f,
    /** Fine pitch in cents, -100..100. */
    val finePitch: Float = 0f,
    /** Release time scale, 0..1 where 0.5 is the instrument's own release. */
    val release: Float = 0.5f,
    /** FL-style slide note: glides playing notes to this pitch instead of starting a new note. */
    val slide: Boolean = false,
    /** Muted notes stay in the pattern but do not sound. */
    val muted: Boolean = false,
) {
    val endTick: Int get() = startTick + lengthTicks

    companion object {
        const val DEFAULT_VELOCITY = 100
    }
}

@Serializable
data class Channel(
    val id: Int,
    val name: String,
    val instrument: Instrument,
    /** 0..1 */
    val volume: Float = 0.8f,
    /** -1 (left) .. 1 (right) */
    val pan: Float = 0f,
    val muted: Boolean = false,
    val solo: Boolean = false,
    /** Index into the UI's channel colour palette. */
    val color: Int = 0,
    /** Mixer insert this channel plays through. 0 = Master. */
    val mixerTrack: Int = 0,
)

/** AUTO: the pattern grows to its last note (FL Studio default). FIXED: an explicit length. */
@Serializable
enum class LengthMode { AUTO, FIXED }

/** Longest pattern allowed. */
const val MAX_PATTERN_BARS = 512

@Serializable
data class Pattern(
    val id: Int,
    val name: String,
    /** FIXED: the length. AUTO: the minimum length (the pattern can grow beyond it). */
    val lengthSteps: Int = 16,
    /** Ticks per step: 24 = 16th notes (4/4), 32 = triplet 8ths (12/8). */
    val stepTicks: Int = Timing.TICKS_PER_STEP,
    /** Notes per channel id. Channels without notes have no entry. */
    val notes: Map<Int, List<Note>> = emptyMap(),
    /** Index into the UI's colour palette, used for playlist clips. */
    val color: Int = 0,
    val lengthMode: LengthMode = LengthMode.AUTO,
) {
    /**
     * Effective length. FIXED: [lengthSteps] steps. AUTO: at least [lengthSteps] steps, extended
     * to the end of the last note rounded up to a whole bar.
     */
    @kotlinx.serialization.Transient
    val lengthTicks: Int = run {
        val min = lengthSteps * stepTicks
        if (lengthMode == LengthMode.FIXED) {
            min
        } else {
            val lastEnd = notes.values.maxOfOrNull { list -> list.maxOfOrNull { it.endTick } ?: 0 } ?: 0
            maxOf(min, Timing.ceilTo(lastEnd, Timing.TICKS_PER_BAR)).coerceAtMost(MAX_PATTERN_BARS * Timing.TICKS_PER_BAR)
        }
    }

    /** Number of steps currently shown (follows [lengthTicks]). */
    val stepCount: Int get() = lengthTicks / stepTicks

    /** Furthest tick a note may reach: the end for FIXED, the 512-bar limit for AUTO. */
    val maxTicks: Int get() = if (lengthMode == LengthMode.FIXED) lengthTicks else MAX_PATTERN_BARS * Timing.TICKS_PER_BAR

    val bars: Int get() = (lengthTicks / Timing.TICKS_PER_BAR).coerceAtLeast(1)

    val meter: Meter get() = Meter.forStepTicks(stepTicks)

    fun notesFor(channelId: Int): List<Note> = notes[channelId].orEmpty()

    fun stepTick(step: Int): Int = step * stepTicks
}

@Serializable
data class ScaleSetting(
    /** 0 = C, 1 = C#, ... 11 = B */
    val root: Int = 0,
    val scale: Scale = Scale.MINOR,
    /** When on, the piano roll only offers in-scale notes. */
    val lock: Boolean = false,
)

/** An audio file imported into the project (stored next to the project as WAV). */
@Serializable
data class SampleRef(
    val id: Int,
    val name: String,
    /** File name inside the project's sample folder. */
    val fileName: String,
    val frames: Long = 0,
    val sampleRate: Int = 48_000,
    val channels: Int = 2,
) {
    val seconds: Double get() = if (sampleRate > 0) frames.toDouble() / sampleRate else 0.0
}

@Serializable
data class Project(
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val name: String,
    val tempo: Float = 140f,
    /** 0..1 */
    val swing: Float = 0f,
    val channels: List<Channel>,
    val patterns: List<Pattern>,
    val currentPatternId: Int,
    val scale: ScaleSetting = ScaleSetting(),
    /** Next free id for channels, patterns, clips, effect slots and samples. */
    val nextId: Int,
    val song: Song = Song(),
    val mixer: Mixer = Mixer.default(),
    val samples: List<SampleRef> = emptyList(),
) {
    val currentPattern: Pattern
        get() = patterns.firstOrNull { it.id == currentPatternId } ?: patterns.first()

    fun channel(id: Int): Channel? = channels.firstOrNull { it.id == id }

    fun pattern(id: Int): Pattern? = patterns.firstOrNull { it.id == id }

    fun sample(id: Int): SampleRef? = samples.firstOrNull { it.id == id }

    companion object {
        const val CURRENT_FORMAT_VERSION = 3
    }
}
