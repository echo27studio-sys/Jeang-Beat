package com.jeangbeat.core.model

import kotlin.math.roundToInt

/* Live recording (3.0): turning a key/pad hit during PAT playback into a note. */

object Recording {
    /** Ticks per second at [tempo] BPM. */
    fun ticksPerSecond(tempo: Float): Double = tempo / 60.0 * Timing.TICKS_PER_BEAT

    /**
     * Where a hit belongs in the pattern. [positionTicks] is the engine position when
     * the key was pressed; the player heard the beat [latencyMillis] earlier, so that
     * is taken off (latency compensation). The result is quantized to [snapTicks]
     * and wrapped into the looping range [loopStart, loopEnd).
     */
    fun noteTick(positionTicks: Float, latencyMillis: Double, tempo: Float, snapTicks: Int, loopStart: Int, loopEnd: Int): Int {
        val length = (loopEnd - loopStart).coerceAtLeast(1)
        val heard = positionTicks - latencyMillis / 1000.0 * ticksPerSecond(tempo)
        val grid = snapTicks.coerceAtLeast(1)
        val q = (heard / grid).roundToInt() * grid
        return loopStart + Math.floorMod(q - loopStart, length)
    }
}

/**
 * Adds a recorded note to the current pattern (overdub). A note of the same pitch
 * already starting at [tick] is kept instead of doubled.
 */
fun Project.recordNote(channelId: Int, pitch: Int, tick: Int, lengthTicks: Int, velocity: Int = Note.DEFAULT_VELOCITY): Project {
    if (channel(channelId) == null) return this
    if (currentPattern.notesFor(channelId).any { it.startTick == tick && it.pitch == pitch }) return this
    return editNotes(channelId) { it + Note(tick, lengthTicks.coerceAtLeast(1), pitch, velocity.coerceIn(1, 127)) }
}
