package com.jeangbeat.core.model

import kotlin.math.roundToInt

/*
 * Note-list helpers for the piano roll and the ring view. They work on plain
 * lists; wrap them in Project.editNotes(channelId) { ... } to apply.
 * "Selection" means notes are identified by (startTick, pitch), which is unique
 * inside one channel of one pattern.
 */

data class NoteKey(val startTick: Int, val pitch: Int)

val Note.key: NoteKey get() = NoteKey(startTick, pitch)

/** Applies [transform] to selected notes (all notes when [selection] is empty). */
inline fun List<Note>.mapSelected(selection: Set<NoteKey>, transform: (Note) -> Note): List<Note> =
    map { if (selection.isEmpty() || it.key in selection) transform(it) else it }

/** Moves note starts towards the grid by [strength] (0..1). Optionally quantizes lengths too. */
fun List<Note>.quantized(grid: Int, selection: Set<NoteKey> = emptySet(), strength: Float = 1f, lengths: Boolean = false): List<Note> =
    mapSelected(selection) { n ->
        val target = Timing.quantize(n.startTick, grid)
        val start = (n.startTick + (target - n.startTick) * strength).roundToInt()
        val len = if (lengths) Timing.quantize(n.lengthTicks, grid).coerceAtLeast(grid) else n.lengthTicks
        n.copy(startTick = start, lengthTicks = len)
    }

fun List<Note>.transposed(semitones: Int, selection: Set<NoteKey> = emptySet()): List<Note> =
    mapSelected(selection) { it.copy(pitch = (it.pitch + semitones).coerceIn(0, 127)) }

/**
 * FL Studio "duplicate" (Ctrl+B): copies the selection and places the copy right
 * after it. The shift is the selection span rounded up to [grid]. Copies past
 * [lengthTicks] are dropped. Returns the new list and the keys of the copies.
 */
fun List<Note>.duplicated(selection: Set<NoteKey>, grid: Int, lengthTicks: Int): Pair<List<Note>, Set<NoteKey>> {
    val sel = if (selection.isEmpty()) this else filter { it.key in selection }
    if (sel.isEmpty()) return this to emptySet()
    val start = sel.minOf { it.startTick }
    val end = sel.maxOf { it.endTick }
    val shift = Timing.ceilTo(end - Timing.floorTo(start, grid), grid).coerceAtLeast(grid)
    val copies = sel.map { it.copy(startTick = it.startTick + shift) }.filter { it.startTick < lengthTicks }
    return (this + copies) to copies.map { it.key }.toSet()
}

/** FL Studio "legato": each note stretches to the start of the next note (by start time). */
fun List<Note>.legato(selection: Set<NoteKey> = emptySet(), lengthTicks: Int): List<Note> {
    val starts = map { it.startTick }.distinct().sorted()
    return mapSelected(selection) { n ->
        val next = starts.firstOrNull { it > n.startTick } ?: lengthTicks
        n.copy(lengthTicks = (next - n.startTick).coerceAtLeast(1))
    }
}

/** Mirrors notes in time inside the selection span. */
fun List<Note>.flipped(selection: Set<NoteKey> = emptySet()): List<Note> {
    val sel = if (selection.isEmpty()) this else filter { it.key in selection }
    if (sel.isEmpty()) return this
    val a = sel.minOf { it.startTick }
    val b = sel.maxOf { it.endTick }
    return mapSelected(selection) { it.copy(startTick = a + (b - it.endTick)) }
}

/** Small random velocity variation (±[amount] of full range), deterministic for a given [seed]. */
fun List<Note>.humanized(amount: Float, seed: Int, selection: Set<NoteKey> = emptySet()): List<Note> {
    val rnd = java.util.Random(seed.toLong())
    return mapSelected(selection) { n ->
        val delta = ((rnd.nextFloat() * 2f - 1f) * amount * 127f).roundToInt()
        n.copy(velocity = (n.velocity + delta).coerceIn(1, 127))
    }
}

/** Cuts a note in two at [tick] (absolute, inside the note). */
fun List<Note>.sliced(note: Note, tick: Int): List<Note> {
    if (tick <= note.startTick || tick >= note.endTick) return this
    return (this - note) + note.copy(lengthTicks = tick - note.startTick) +
        note.copy(startTick = tick, lengthTicks = note.endTick - tick)
}

/** Shifts selected notes by [ticks] in time, keeping them inside the pattern. */
fun List<Note>.shifted(ticks: Int, selection: Set<NoteKey>, lengthTicks: Int): List<Note> =
    mapSelected(selection) { it.copy(startTick = (it.startTick + ticks).coerceIn(0, lengthTicks - 1)) }

/** Chord shapes for FL-style chord stamps (semitones above the root). */
enum class ChordType(val label: String, val intervals: List<Int>) {
    NONE("Single note", listOf(0)),
    MAJOR("Major", listOf(0, 4, 7)),
    MINOR("Minor", listOf(0, 3, 7)),
    MAJOR7("Major 7", listOf(0, 4, 7, 11)),
    MINOR7("Minor 7", listOf(0, 3, 7, 10)),
    DOMINANT7("7th", listOf(0, 4, 7, 10)),
    SUS2("Sus 2", listOf(0, 2, 7)),
    SUS4("Sus 4", listOf(0, 5, 7)),
    DIMINISHED("Diminished", listOf(0, 3, 6)),
    AUGMENTED("Augmented", listOf(0, 4, 8)),
    POWER("Power (5th)", listOf(0, 7, 12)),
    ;

    fun pitches(root: Int): List<Int> = intervals.map { root + it }.filter { it in 0..127 }
}

/** Notes for a chord stamp at [start]. */
fun chordNotes(root: Int, type: ChordType, start: Int, length: Int, velocity: Int = Note.DEFAULT_VELOCITY): List<Note> =
    type.pitches(root).map { Note(start, length, it, velocity) }

// ---- Ring view (rhythm circle) ----------------------------------------------

/**
 * Euclidean rhythm: [hits] onsets spread as evenly as possible over [steps],
 * then rotated by [rotation] steps. Returns 0-based step indices, sorted.
 * E(3,8) = [0,3,6] (tresillo), E(5,8) = [0,2,4,5,7]-family (cinquillo, rotated).
 */
fun euclid(hits: Int, steps: Int, rotation: Int = 0): List<Int> {
    if (steps <= 0) return emptyList()
    val k = hits.coerceIn(0, steps)
    if (k == 0) return emptyList()
    val onsets = (0 until steps).filter { i -> (i * k) % steps < k }
    return onsets.map { Math.floorMod(it + rotation, steps) }.sorted()
}

/** Steps (0-based) where the channel has a note starting exactly on the step grid. */
fun Pattern.channelSteps(channelId: Int): List<Int> =
    notesFor(channelId).filter { it.startTick % stepTicks == 0 }.map { it.startTick / stepTicks }.distinct().sorted()

/** Replaces the channel's on-grid notes with notes on [steps]. Off-grid notes are kept. */
fun Project.setChannelSteps(channelId: Int, steps: Collection<Int>): Project {
    val channel = channel(channelId) ?: return this
    return updateCurrentPattern { pattern ->
        val keep = pattern.notesFor(channelId).filter { it.startTick % pattern.stepTicks != 0 }
        val existing = pattern.notesFor(channelId).associateBy { it.startTick }
        val new = steps.filter { it in 0 until pattern.lengthSteps }.map { s ->
            val tick = pattern.stepTick(s)
            existing[tick] ?: Note(tick, pattern.stepTicks, channel.instrument.defaultPitch)
        }
        pattern.withNotes(channelId, pattern.normalized(keep + new))
    }
}

/** Rotates all of a channel's notes by [steps] around the pattern (ring drag). */
fun Project.rotateChannel(channelId: Int, steps: Int): Project = updateCurrentPattern { pattern ->
    val shift = steps * pattern.stepTicks
    val rotated = pattern.notesFor(channelId).map {
        it.copy(startTick = Math.floorMod(it.startTick + shift, pattern.lengthTicks))
    }
    pattern.withNotes(channelId, pattern.normalized(rotated))
}

/** One-line description of the pattern, e.g. "Kick on 1, 8, 11 · Snare on 9" (1-based steps). */
fun Project.describePattern(pattern: Pattern = currentPattern, maxChannels: Int = 3): String =
    channels.asSequence()
        .filter { !it.instrument.isMelodic }
        .map { it to pattern.channelSteps(it.id) }
        .filter { (_, steps) -> steps.isNotEmpty() && steps.size <= 8 }
        .take(maxChannels)
        .joinToString(" · ") { (ch, steps) -> "${ch.name} on ${steps.joinToString(", ") { (it + 1).toString() }}" }
