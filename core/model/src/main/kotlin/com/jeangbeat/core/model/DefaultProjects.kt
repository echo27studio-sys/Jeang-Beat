package com.jeangbeat.core.model

object DefaultProjects {

    /** The standard channel line-up every new project starts with; each on its own mixer insert. */
    private fun channels(): List<Channel> = listOf(
        Channel(1, "Kick", Instrument.Drum(DrumSound.KICK), volume = 0.85f, color = 0, mixerTrack = 1),
        Channel(2, "Snare", Instrument.Drum(DrumSound.SNARE), color = 1, mixerTrack = 2),
        Channel(3, "Clap", Instrument.Drum(DrumSound.CLAP), volume = 0.7f, color = 2, mixerTrack = 2),
        Channel(4, "Hi-Hat", Instrument.Drum(DrumSound.HAT_CLOSED), volume = 0.6f, pan = 0.15f, color = 3, mixerTrack = 3),
        Channel(5, "Open Hat", Instrument.Drum(DrumSound.HAT_OPEN), volume = 0.55f, pan = 0.15f, color = 3, mixerTrack = 3),
        Channel(6, "Perc", Instrument.Drum(DrumSound.PERC), volume = 0.6f, pan = -0.2f, color = 4, mixerTrack = 5),
        Channel(7, "Chhing", Instrument.Drum(DrumSound.CHHING), volume = 0.5f, pan = -0.3f, color = 5, mixerTrack = 5),
        Channel(8, "808", Instrument.Bass808(), volume = 0.8f, color = 6, mixerTrack = 4),
        Channel(9, "Keys", Instrument.Synth(), volume = 0.55f, color = 7, mixerTrack = 6),
    )

    private val insertNames = mapOf(1 to "Kick", 2 to "Snare/Clap", 3 to "Hats", 4 to "808", 5 to "Perc", 6 to "Keys", 7 to "Reverb", 8 to "Delay")
    private val insertColors = mapOf(1 to 0, 2 to 1, 3 to 3, 4 to 6, 5 to 5, 6 to 7, 7 to 4, 8 to 4)

    /** Mixer with named inserts, plus Reverb (7) and Delay (8) return buses. */
    private fun mixer(): Mixer {
        val base = Mixer.default()
        return base.copy(
            tracks = base.tracks.map { t ->
                val named = insertNames[t.index]?.let { t.copy(name = it, color = insertColors[t.index] ?: -1) } ?: t
                when (t.index) {
                    7 -> named.copy(effects = listOf(EffectSlot(90, EffectType.REVERB, mix = 1f)))
                    8 -> named.copy(effects = listOf(EffectSlot(91, EffectType.DELAY, mix = 1f)))
                    0 -> named.copy(effects = listOf(EffectSlot(92, EffectType.LIMITER)))
                    else -> named
                }
            },
        )
    }

    private const val FIRST_FREE_ID = 100

    fun empty(name: String, patternName: String = "Pattern 1"): Project = Project(
        name = name,
        tempo = 140f,
        channels = channels(),
        patterns = listOf(Pattern(id = 10, name = patternName, lengthSteps = 16)),
        currentPatternId = 10,
        scale = ScaleSetting(root = 0, scale = Scale.MINOR),
        nextId = FIRST_FREE_ID,
        mixer = mixer(),
        song = Song(clips = listOf(Clip.PatternClip(id = 20, trackId = 1, startTick = 0, lengthTicks = Timing.TICKS_PER_BAR, patternId = 10))),
    )

    /** A short trap-style demo beat in C minor, arranged over 8 bars with a mixed sound. */
    fun demo(name: String, patternName: String = "Pattern 1"): Project {
        val step = Timing.TICKS_PER_STEP
        fun hits(vararg steps: Int, velocity: Int = 100, pitch: Int = ROOT_PITCH) =
            steps.map { Note(it * step, step, pitch, velocity) }

        val hats = (0 until 16 step 2).map { Note(it * step, step, ROOT_PITCH, if (it % 4 == 0) 100 else 72) } +
            Note(15 * step, step, ROOT_PITCH, 64)

        val drums = mapOf(
            1 to hits(0, 7, 10),
            2 to hits(8),
            3 to hits(8, velocity = 80),
            4 to hats.sortedBy { it.startTick },
            7 to hits(12, velocity = 70),
        )
        val bass = mapOf(
            8 to listOf(
                Note(0 * step, 6 * step, 36, 110),
                Note(5 * step, 3 * step, 39, 105, slide = true),
                Note(10 * step, 6 * step, 36, 110),
            ),
        )
        val keys = mapOf(
            9 to listOf(
                Note(0, 8 * step, 60, 80), Note(0, 8 * step, 63, 80), Note(0, 8 * step, 67, 80),
                Note(8 * step, 8 * step, 56, 80), Note(8 * step, 8 * step, 60, 80), Note(8 * step, 8 * step, 63, 80),
            ),
        )
        val bar = Timing.TICKS_PER_BAR
        val base = empty(name, patternName)
        val patterns = listOf(
            Pattern(10, patternName, notes = drums, color = 0),
            Pattern(11, "808", notes = bass, color = 6),
            Pattern(12, "Chords", notes = keys, color = 7),
        )
        val clips = listOf(
            // Long clips repeat their 1-bar patterns, so they don't follow the pattern length.
            Clip.PatternClip(20, 1, 0, 8 * bar, 10, followsPattern = false),
            Clip.PatternClip(21, 2, 2 * bar, 6 * bar, 11, followsPattern = false),
            Clip.PatternClip(22, 3, 0, 8 * bar, 12, followsPattern = false),
        )
        val mixed = base.copy(patterns = patterns, song = base.song.copy(clips = clips))
            // Keys and snare get some reverb; the 808 ducks under the kick.
            .setSend(6, 7, 0.3f)
            .setSend(2, 7, 0.15f)
            .updateMixerTrack(4) { it.copy(effects = listOf(EffectSlot(93, EffectType.COMPRESSOR, params = listOf(-24f, 6f, 1f, 90f, 2f, 1f)))) }
        return mixed
    }

    /**
     * Upgrades a project saved by an older app version. Version 1 had no mixer
     * routing and no song: each channel gets its own insert and the current
     * pattern is placed once in the Playlist.
     */
    fun migrate(project: Project): Project {
        if (project.formatVersion >= Project.CURRENT_FORMAT_VERSION) return project
        val from = project.formatVersion
        var p = project.copy(formatVersion = Project.CURRENT_FORMAT_VERSION)
        if (from < 3) {
            // 2.x patterns had a fixed length; keep it so old songs sound the same. A clip
            // follows its pattern only if it was exactly the pattern's length.
            p = p.copy(patterns = p.patterns.map { it.copy(lengthMode = LengthMode.FIXED) })
            p = p.copy(
                song = p.song.copy(
                    clips = p.song.clips.map { c ->
                        if (c is Clip.PatternClip) c.copy(followsPattern = c.lengthTicks == p.pattern(c.patternId)?.lengthTicks) else c
                    },
                ),
            )
        }
        if (p.channels.all { it.mixerTrack == 0 }) {
            p = p.copy(channels = p.channels.mapIndexed { i, c -> c.copy(mixerTrack = if (i < MIXER_INSERTS) i + 1 else 0) })
            p = p.copy(
                mixer = p.mixer.copy(
                    tracks = p.mixer.tracks.map { t ->
                        p.channels.firstOrNull { it.mixerTrack == t.index }?.let { t.copy(name = it.name, color = it.color) } ?: t
                    },
                ),
            )
        }
        if (p.song.clips.isEmpty()) {
            val pattern = p.currentPattern
            p = p.copy(
                song = p.song.copy(clips = listOf(Clip.PatternClip(p.nextId, 1, 0, pattern.lengthTicks, pattern.id))),
                nextId = p.nextId + 1,
            )
        }
        return p
    }
}
