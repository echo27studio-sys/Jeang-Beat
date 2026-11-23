package com.jeangbeat.core.model

object DefaultProjects {

    /** The standard channel line-up every new project starts with. */
    private fun channels(): List<Channel> = listOf(
        Channel(1, "Kick", Instrument.Drum(DrumSound.KICK), volume = 0.85f, color = 0),
        Channel(2, "Snare", Instrument.Drum(DrumSound.SNARE), color = 1),
        Channel(3, "Clap", Instrument.Drum(DrumSound.CLAP), volume = 0.7f, color = 2),
        Channel(4, "Hi-Hat", Instrument.Drum(DrumSound.HAT_CLOSED), volume = 0.6f, pan = 0.15f, color = 3),
        Channel(5, "Open Hat", Instrument.Drum(DrumSound.HAT_OPEN), volume = 0.55f, pan = 0.15f, color = 3),
        Channel(6, "Perc", Instrument.Drum(DrumSound.PERC), volume = 0.6f, pan = -0.2f, color = 4),
        Channel(7, "Chhing", Instrument.Drum(DrumSound.CHHING), volume = 0.5f, pan = -0.3f, color = 5),
        Channel(8, "808", Instrument.Bass808(), volume = 0.8f, color = 6),
        Channel(9, "Keys", Instrument.Synth(), volume = 0.55f, color = 7),
    )

    private const val FIRST_FREE_ID = 100

    fun empty(name: String, patternName: String = "Pattern 1"): Project = Project(
        name = name,
        tempo = 140f,
        channels = channels(),
        patterns = listOf(Pattern(id = 10, name = patternName, lengthSteps = 16)),
        currentPatternId = 10,
        scale = ScaleSetting(root = 0, scale = Scale.MINOR),
        nextId = FIRST_FREE_ID,
    )

    /** A short trap-style demo beat in C minor so new users hear something straight away. */
    fun demo(name: String, patternName: String = "Pattern 1"): Project {
        val step = Timing.TICKS_PER_STEP
        fun hits(vararg steps: Int, velocity: Int = 100, pitch: Int = ROOT_PITCH) =
            steps.map { Note(it * step, step, pitch, velocity) }

        val hats = (0 until 16 step 2).map { Note(it * step, step, ROOT_PITCH, if (it % 4 == 0) 100 else 72) } +
            Note(15 * step, step, ROOT_PITCH, 64)

        val notes = mapOf(
            1 to hits(0, 7, 10),
            2 to hits(8),
            3 to hits(8, velocity = 80),
            4 to hats.sortedBy { it.startTick },
            7 to hits(12, velocity = 70),
            8 to listOf(
                Note(0 * step, 6 * step, 36, 110),
                Note(7 * step, 3 * step, 39, 105),
                Note(10 * step, 6 * step, 36, 110),
            ),
            9 to listOf(
                Note(0, 8 * step, 60, 80), Note(0, 8 * step, 63, 80), Note(0, 8 * step, 67, 80),
                Note(8 * step, 8 * step, 56, 80), Note(8 * step, 8 * step, 60, 80), Note(8 * step, 8 * step, 63, 80),
            ),
        )
        return empty(name, patternName).updateCurrentPattern { it.copy(notes = notes) }
    }
}
