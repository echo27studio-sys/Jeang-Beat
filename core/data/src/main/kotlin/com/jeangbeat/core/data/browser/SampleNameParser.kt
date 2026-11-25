package com.jeangbeat.core.data.browser

/**
 * Reads tempo, key and kind from a sample's name and folder, the way sample packs
 * name their files: "Kick_808_C#_140bpm.wav", "Loops/Dark Piano Loop 90 Am.wav".
 */
object SampleNameParser {
    data class Parsed(val bpm: Int?, val key: String?, val category: SampleCategory)

    private val SEPARATORS = Regex("[\\s_\\-.()\\[\\],]+")
    private val BPM_WITH_UNIT = Regex("(?i)(?<![0-9])([0-9]{2,3})\\s*_?bpm|bpm\\s*_?([0-9]{2,3})(?![0-9])")
    private val KEY_TOKEN = Regex("^([A-G])([#♯]|b|♭)?(m|min|minor|maj|major)?$")
    private val NUMBER = Regex("^[0-9]{2,3}$")

    private val VOCALS = listOf("vocal", "vox", "acapella", "acappella", "chant", "adlib", "voice", "phrase")
    private val FX = listOf("fx", "riser", "impact", "sweep", "uplifter", "downlifter", "transition", "noise", "whoosh", "foley")
    private val LOOP = listOf("loop", "loops")
    private val DRUMS = listOf(
        "kick", "kik", "bd", "snare", "snr", "clap", "hat", "hihat", "hh", "oh", "ch", "cymbal", "crash", "ride",
        "perc", "percussion", "tom", "rim", "shaker", "drum", "drums", "chhing", "skor", "snap", "808kick", "conga", "bongo",
    )
    private val INSTRUMENTS = listOf(
        "808", "bass", "piano", "keys", "synth", "pad", "lead", "pluck", "guitar", "string", "strings", "bell", "brass",
        "flute", "organ", "chord", "chords", "melody", "roneat", "tro", "khim", "pinpeat", "sralai", "arp",
    )

    fun parse(path: String): Parsed {
        val file = path.substringAfterLast('/').substringBeforeLast('.')
        val tokens = file.split(SEPARATORS).filter { it.isNotEmpty() }
        val dirTokens = path.substringBeforeLast('/', "").lowercase().split('/', ' ', '_', '-').filter { it.isNotEmpty() }
        val lower = tokens.map { it.lowercase() }

        var bpm = BPM_WITH_UNIT.find(file)?.let { m -> (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull() }
        val isLoop = lower.any { it in LOOP } || dirTokens.any { it in LOOP }
        if (bpm == null && isLoop) {
            // Loops often have a bare tempo: "Piano Loop 90 Am".
            bpm = tokens.firstOrNull { NUMBER.matches(it) && it.toInt() in 60..200 }?.toInt()
        }
        bpm = bpm?.takeIf { it in 40..300 }

        val category = when {
            lower.any { w -> VOCALS.any { w.startsWith(it) } } || dirTokens.any { w -> VOCALS.any { w.startsWith(it) } } -> SampleCategory.VOCALS
            lower.any { it in FX } || dirTokens.any { it in FX } -> SampleCategory.FX
            isLoop -> SampleCategory.LOOPS
            lower.any { it in DRUMS } || dirTokens.any { it in DRUMS } -> SampleCategory.DRUMS
            bpm != null -> SampleCategory.LOOPS
            lower.any { it in INSTRUMENTS } || dirTokens.any { it in INSTRUMENTS } -> SampleCategory.INSTRUMENTS
            else -> SampleCategory.ONE_SHOTS
        }
        return Parsed(bpm, key(tokens), category)
    }

    /** "C#m", "Bb", "F#", "Am"… or null. Accepts "C# minor" written as two words. */
    fun key(tokens: List<String>): String? {
        for ((i, t) in tokens.withIndex()) {
            val m = KEY_TOKEN.matchEntire(t) ?: continue
            val (letter, accidental, quality) = m.destructured
            val acc = when (accidental) { "#", "♯" -> "#"; "b", "♭" -> "b"; else -> "" }
            val next = tokens.getOrNull(i + 1)?.lowercase()
            val minor = when {
                quality.isNotEmpty() -> quality.startsWith("m") && !quality.startsWith("maj")
                next == "minor" || next == "min" -> true
                else -> false
            }
            // A bare single letter is only a key when nothing else in the name looks like one (avoids "Kick A").
            if (acc.isEmpty() && quality.isEmpty() && next != "minor" && next != "min" && next != "major" && next != "maj" && i == 0) continue
            return letter + acc + if (minor) "m" else ""
        }
        return null
    }
}
