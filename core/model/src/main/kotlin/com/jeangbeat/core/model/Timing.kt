package com.jeangbeat.core.model

/** Musical timing constants. Must match core/audio/src/main/cpp/engine/Constants.h. */
object Timing {
    /** Ticks per quarter note (one beat). */
    const val TICKS_PER_BEAT = 96

    /** Ticks per 16th-note step. */
    const val TICKS_PER_STEP = 24

    /** Ticks per bar. Both 4/4 (16 x 24) and 12/8 (12 x 32) bars are 384 ticks. */
    const val TICKS_PER_BAR = 384

    const val STEPS_PER_BEAT = TICKS_PER_BEAT / TICKS_PER_STEP

    const val MIN_TEMPO = 40f
    const val MAX_TEMPO = 300f

    val PATTERN_BARS = listOf(1, 2, 4)

    fun stepToTick(step: Int): Int = step * TICKS_PER_STEP

    /** Rounds [tick] to the nearest multiple of [grid] ticks. */
    fun quantize(tick: Int, grid: Int): Int {
        require(grid > 0) { "grid must be positive" }
        return Math.floorDiv(tick + grid / 2, grid) * grid
    }

    /** Rounds [tick] down to a multiple of [grid]. */
    fun floorTo(tick: Int, grid: Int): Int = Math.floorDiv(tick, grid) * grid

    /** Rounds [tick] up to a multiple of [grid]. */
    fun ceilTo(tick: Int, grid: Int): Int = Math.floorDiv(tick + grid - 1, grid) * grid

    /** "bar:beat:step" (1-based) for the transport display. */
    fun position(tick: Int): String {
        val bar = tick / TICKS_PER_BAR + 1
        val beat = (tick % TICKS_PER_BAR) / TICKS_PER_BEAT + 1
        val step = (tick % TICKS_PER_BEAT) / TICKS_PER_STEP
        return "%02d:%d:%02d".format(bar, beat, step)
    }
}

/** Pattern meter: how a bar is divided into steps. */
enum class Meter(val stepTicks: Int, val stepsPerBar: Int, val stepsPerBeat: Int, val label: String) {
    FOUR_FOUR(24, 16, 4, "4/4"),
    TWELVE_EIGHT(32, 12, 3, "12/8"),
    ;

    companion object {
        fun forStepTicks(ticks: Int): Meter = entries.firstOrNull { it.stepTicks == ticks } ?: FOUR_FOUR
    }
}

/** Snap / grid sizes, in ticks. */
enum class Snap(val ticks: Int, val label: String) {
    BAR(Timing.TICKS_PER_BAR, "Bar"),
    BEAT(Timing.TICKS_PER_BEAT, "1/4"),
    EIGHTH(Timing.TICKS_PER_BEAT / 2, "1/8"),
    STEP(Timing.TICKS_PER_STEP, "1/16"),
    STEP_TRIPLET(Timing.TICKS_PER_BEAT / 6, "1/16T"),
    THIRTY_SECOND(Timing.TICKS_PER_STEP / 2, "1/32"),
    NONE(1, "None"),
}

/** Note-repeat rates, in ticks. */
enum class RepeatRate(val ticks: Int) {
    OFF(0),
    EIGHTH(Timing.TICKS_PER_BEAT / 2),
    STEP(Timing.TICKS_PER_STEP),
    STEP_TRIPLET(Timing.TICKS_PER_BEAT / 6),
    THIRTY_SECOND(Timing.TICKS_PER_STEP / 2),
}
