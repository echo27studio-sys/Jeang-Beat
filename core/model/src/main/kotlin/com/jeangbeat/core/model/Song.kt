package com.jeangbeat.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A Playlist row. Clips on a track keep their own routing; the track only groups, mutes and solos. */
@Serializable
data class PlaylistTrack(
    val id: Int,
    val name: String,
    /** Palette colour index, or -1 for the default. */
    val color: Int = -1,
    val muted: Boolean = false,
    val solo: Boolean = false,
)

/** A block on the Playlist timeline. Ticks are song positions. */
@Serializable
sealed interface Clip {
    val id: Int
    val trackId: Int
    val startTick: Int
    val lengthTicks: Int
    val muted: Boolean
    val endTick: Int get() = startTick + lengthTicks

    /** Plays a pattern. If the clip is longer than the pattern, the pattern repeats. */
    @Serializable
    @SerialName("pattern")
    data class PatternClip(
        override val id: Int,
        override val trackId: Int,
        override val startTick: Int,
        override val lengthTicks: Int,
        val patternId: Int,
        /** Where inside the pattern the clip starts (after slicing). */
        val offsetTicks: Int = 0,
        override val muted: Boolean = false,
        /** True: the clip grows/shrinks with an auto-length pattern. False once resized by hand. */
        val followsPattern: Boolean = true,
    ) : Clip

    /** Plays an imported audio file, e.g. a vocal. */
    @Serializable
    @SerialName("audio")
    data class AudioClip(
        override val id: Int,
        override val trackId: Int,
        override val startTick: Int,
        override val lengthTicks: Int,
        val sampleId: Int,
        val offsetTicks: Int = 0,
        override val muted: Boolean = false,
        /** Linear gain, 0..2. */
        val gain: Float = 1f,
        /** Mixer insert the audio plays through. 0 = Master. */
        val mixerTrack: Int = 0,
    ) : Clip

    /** Moves a knob over time. Values are 0..1 and mapped to the target's range. */
    @Serializable
    @SerialName("automation")
    data class AutomationClip(
        override val id: Int,
        override val trackId: Int,
        override val startTick: Int,
        override val lengthTicks: Int,
        val target: AutomationTarget,
        /** Sorted by tick, ticks relative to the clip start. */
        val points: List<AutoPoint> = listOf(AutoPoint(0, 0.5f), AutoPoint(lengthTicks, 0.5f)),
        override val muted: Boolean = false,
    ) : Clip
}

@Serializable
data class AutoPoint(val tick: Int, val value: Float)

/** What an automation clip controls. */
@Serializable
sealed interface AutomationTarget {
    @Serializable
    @SerialName("mixer_volume")
    data class MixerVolume(val track: Int) : AutomationTarget

    @Serializable
    @SerialName("mixer_pan")
    data class MixerPan(val track: Int) : AutomationTarget

    @Serializable
    @SerialName("channel_volume")
    data class ChannelVolume(val channelId: Int) : AutomationTarget

    @Serializable
    @SerialName("channel_pan")
    data class ChannelPan(val channelId: Int) : AutomationTarget

    /** [param] indexes EffectType.params of the effect in slot [slotId] on mixer [track]. */
    @Serializable
    @SerialName("effect_param")
    data class EffectParam(val track: Int, val slotId: Int, val param: Int) : AutomationTarget
}

@Serializable
data class Song(
    val tracks: List<PlaylistTrack> = defaultTracks(),
    val clips: List<Clip> = emptyList(),
    /** Loop region in song ticks; -1 = loop the whole song. */
    val loopStart: Int = -1,
    val loopEnd: Int = -1,
) {
    val hasLoop: Boolean get() = loopStart >= 0 && loopEnd > loopStart

    /** Song length: end of the last clip rounded up to a whole bar, at least one bar. */
    val lengthTicks: Int
        get() = Timing.ceilTo(clips.maxOfOrNull { it.endTick } ?: 0, Timing.TICKS_PER_BAR)
            .coerceAtLeast(Timing.TICKS_PER_BAR)

    fun track(id: Int): PlaylistTrack? = tracks.firstOrNull { it.id == id }

    fun clip(id: Int): Clip? = clips.firstOrNull { it.id == id }

    companion object {
        const val DEFAULT_TRACK_COUNT = 8
        const val MAX_TRACKS = 64

        /** Track ids 1..8 are reserved for the default tracks. */
        fun defaultTracks(): List<PlaylistTrack> = (1..DEFAULT_TRACK_COUNT).map { PlaylistTrack(it, "Track $it") }
    }
}
