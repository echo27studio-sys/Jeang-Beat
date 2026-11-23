package com.jeangbeat.core.model

/** Musical timing constants. Must match core/audio/src/main/cpp/engine/Constants.h. */
object Timing {
    /** Ticks per quarter note (one beat). */
    const val TICKS_PER_BEAT = 96

    /** Ticks per 16th-note step. */
    const val TICKS_PER_STEP = 24

    const val STEPS_PER_BEAT = TICKS_PER_BEAT / TICKS_PER_STEP

    const val MIN_TEMPO = 40f
    const val MAX_TEMPO = 300f

    val PATTERN_LENGTHS = listOf(16, 32, 64)

    fun stepToTick(step: Int): Int = step * TICKS_PER_STEP

    /** Rounds [tick] to the nearest multiple of [grid] ticks. */
    fun quantize(tick: Int, grid: Int): Int {
        require(grid > 0) { "grid must be positive" }
        return Math.floorDiv(tick + grid / 2, grid) * grid
    }
}

/** Snap / grid sizes for the piano roll, in ticks. */
enum class Snap(val ticks: Int) {
    BAR(Timing.TICKS_PER_BEAT * 4),
    BEAT(Timing.TICKS_PER_BEAT),
    EIGHTH(Timing.TICKS_PER_BEAT / 2),
    STEP(Timing.TICKS_PER_STEP),
    STEP_TRIPLET(Timing.TICKS_PER_BEAT / 6),
    THIRTY_SECOND(Timing.TICKS_PER_STEP / 2),
}

/** Note-repeat rates for the drum pads, in ticks. */
enum class RepeatRate(val ticks: Int) {
    OFF(0),
    EIGHTH(Timing.TICKS_PER_BEAT / 2),
    STEP(Timing.TICKS_PER_STEP),
    STEP_TRIPLET(Timing.TICKS_PER_BEAT / 6),
    THIRTY_SECOND(Timing.TICKS_PER_STEP / 2),
}
