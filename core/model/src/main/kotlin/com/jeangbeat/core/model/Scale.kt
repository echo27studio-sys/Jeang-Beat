package com.jeangbeat.core.model

/** Musical scales as semitone intervals from the root. */
enum class Scale(val intervals: List<Int>) {
    MAJOR(listOf(0, 2, 4, 5, 7, 9, 11)),
    MINOR(listOf(0, 2, 3, 5, 7, 8, 10)),
    HARMONIC_MINOR(listOf(0, 2, 3, 5, 7, 8, 11)),
    DORIAN(listOf(0, 2, 3, 5, 7, 9, 10)),
    PHRYGIAN(listOf(0, 1, 3, 5, 7, 8, 10)),
    MAJOR_PENTATONIC(listOf(0, 2, 4, 7, 9)),
    MINOR_PENTATONIC(listOf(0, 3, 5, 7, 10)),
    BLUES(listOf(0, 3, 5, 6, 7, 10)),
    CHROMATIC((0..11).toList()),
    ;

    fun contains(pitch: Int, root: Int): Boolean = Math.floorMod(pitch - root, 12) in intervals

    /** Nearest in-scale pitch; ties resolve downwards. */
    fun snap(pitch: Int, root: Int): Int {
        if (contains(pitch, root)) return pitch
        for (distance in 1..6) {
            if (contains(pitch - distance, root)) return pitch - distance
            if (contains(pitch + distance, root)) return pitch + distance
        }
        return pitch
    }

    /** In-scale pitches from [low] to [high] inclusive, ascending. */
    fun pitchesIn(low: Int, high: Int, root: Int): List<Int> = (low..high).filter { contains(it, root) }
}

val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

/** "C4" style name, where MIDI 60 = C4. */
fun pitchName(pitch: Int): String = NOTE_NAMES[Math.floorMod(pitch, 12)] + (Math.floorDiv(pitch, 12) - 1)

fun isBlackKey(pitch: Int): Boolean = Math.floorMod(pitch, 12) in setOf(1, 3, 6, 8, 10)

fun ScaleSetting.allows(pitch: Int): Boolean = !lock || scale.contains(pitch, root)
