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
)

@Serializable
data class Pattern(
    val id: Int,
    val name: String,
    val lengthSteps: Int = 16,
    /** Notes per channel id. Channels without notes have no entry. */
    val notes: Map<Int, List<Note>> = emptyMap(),
) {
    val lengthTicks: Int get() = lengthSteps * Timing.TICKS_PER_STEP

    fun notesFor(channelId: Int): List<Note> = notes[channelId].orEmpty()
}

@Serializable
data class ScaleSetting(
    /** 0 = C, 1 = C#, ... 11 = B */
    val root: Int = 0,
    val scale: Scale = Scale.MINOR,
    /** When on, the piano roll and melodic pads only offer in-scale notes. */
    val lock: Boolean = false,
)

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
    /** Next free id for channels and patterns. */
    val nextId: Int,
) {
    val currentPattern: Pattern
        get() = patterns.firstOrNull { it.id == currentPatternId } ?: patterns.first()

    fun channel(id: Int): Channel? = channels.firstOrNull { it.id == id }

    companion object {
        const val CURRENT_FORMAT_VERSION = 1
    }
}
