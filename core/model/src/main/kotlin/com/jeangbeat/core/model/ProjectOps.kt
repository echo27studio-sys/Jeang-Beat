package com.jeangbeat.core.model

/*
 * Pure editing operations. Every function returns a new Project and leaves the
 * receiver untouched, which makes undo/redo a simple list of snapshots.
 */

const val MAX_CHANNELS = 32
const val MAX_PATTERNS = 99
const val CHANNEL_COLOR_COUNT = 8

fun Project.updatePattern(patternId: Int, transform: (Pattern) -> Pattern): Project =
    copy(patterns = patterns.map { if (it.id == patternId) transform(it) else it })

fun Project.updateCurrentPattern(transform: (Pattern) -> Pattern): Project =
    updatePattern(currentPattern.id, transform)

fun Pattern.withNotes(channelId: Int, notes: List<Note>): Pattern {
    val updated = if (notes.isEmpty()) this.notes - channelId else this.notes + (channelId to notes)
    return copy(notes = updated)
}

// ---- Step sequencer ------------------------------------------------------

/** True when a note starts exactly on [step] for the channel. */
fun Pattern.isStepOn(channelId: Int, step: Int): Boolean {
    val tick = Timing.stepToTick(step)
    return notesFor(channelId).any { it.startTick == tick }
}

fun Pattern.stepVelocity(channelId: Int, step: Int): Int? {
    val tick = Timing.stepToTick(step)
    return notesFor(channelId).firstOrNull { it.startTick == tick }?.velocity
}

fun Project.toggleStep(channelId: Int, step: Int, velocity: Int = Note.DEFAULT_VELOCITY): Project {
    val channel = channel(channelId) ?: return this
    if (step !in 0 until currentPattern.lengthSteps) return this
    return updateCurrentPattern { pattern ->
        val tick = Timing.stepToTick(step)
        val existing = pattern.notesFor(channelId)
        val updated = if (existing.any { it.startTick == tick }) {
            existing.filterNot { it.startTick == tick }
        } else {
            val note = Note(tick, Timing.TICKS_PER_STEP, channel.instrument.defaultPitch, velocity.coerceIn(1, 127))
            (existing + note).sortedBy { it.startTick }
        }
        pattern.withNotes(channelId, updated)
    }
}

fun Project.setStepVelocity(channelId: Int, step: Int, velocity: Int): Project = updateCurrentPattern { pattern ->
    val tick = Timing.stepToTick(step)
    pattern.withNotes(
        channelId,
        pattern.notesFor(channelId).map { if (it.startTick == tick) it.copy(velocity = velocity.coerceIn(1, 127)) else it },
    )
}

// ---- Notes (piano roll) --------------------------------------------------

private fun Note.clampedTo(pattern: Pattern): Note {
    val start = startTick.coerceIn(0, pattern.lengthTicks - 1)
    return copy(
        startTick = start,
        lengthTicks = lengthTicks.coerceIn(1, pattern.lengthTicks - start),
        pitch = pitch.coerceIn(0, 127),
        velocity = velocity.coerceIn(1, 127),
    )
}

/** Adds a note, replacing any note with the same start and pitch. */
fun Project.addNote(channelId: Int, note: Note): Project {
    if (channel(channelId) == null) return this
    return updateCurrentPattern { pattern ->
        val n = note.clampedTo(pattern)
        val others = pattern.notesFor(channelId).filterNot { it.startTick == n.startTick && it.pitch == n.pitch }
        pattern.withNotes(channelId, (others + n).sortedWith(compareBy({ it.startTick }, { it.pitch })))
    }
}

fun Project.removeNote(channelId: Int, note: Note): Project = updateCurrentPattern { pattern ->
    pattern.withNotes(channelId, pattern.notesFor(channelId) - note)
}

/** Replaces [old] with [new] (used for move, resize and velocity edits). */
fun Project.replaceNote(channelId: Int, old: Note, new: Note): Project = updateCurrentPattern { pattern ->
    val n = new.clampedTo(pattern)
    val others = pattern.notesFor(channelId).filterNot { it == old }
        .filterNot { it.startTick == n.startTick && it.pitch == n.pitch }
    pattern.withNotes(channelId, (others + n).sortedWith(compareBy({ it.startTick }, { it.pitch })))
}

fun Project.clearChannel(channelId: Int): Project = updateCurrentPattern { it.withNotes(channelId, emptyList()) }

/**
 * Records a live hit (from the pads) into the current pattern, quantized to
 * [gridTicks]. Hits quantized past the end wrap round to the start.
 */
fun Project.recordHit(channelId: Int, tick: Int, pitch: Int, velocity: Int, gridTicks: Int): Project {
    val pattern = currentPattern
    val start = Math.floorMod(Timing.quantize(tick, gridTicks), pattern.lengthTicks)
    return addNote(channelId, Note(start, gridTicks, pitch, velocity))
}

// ---- Patterns ------------------------------------------------------------

fun Project.setPatternLength(steps: Int): Project = updateCurrentPattern { pattern ->
    val lengthTicks = steps * Timing.TICKS_PER_STEP
    pattern.copy(
        lengthSteps = steps,
        notes = pattern.notes
            .mapValues { (_, notes) ->
                notes.filter { it.startTick < lengthTicks }
                    .map { it.copy(lengthTicks = it.lengthTicks.coerceAtMost(lengthTicks - it.startTick)) }
            }
            .filterValues { it.isNotEmpty() },
    )
}

fun Project.addPattern(name: String, copyCurrent: Boolean): Project {
    if (patterns.size >= MAX_PATTERNS) return this
    val source = currentPattern
    val pattern = if (copyCurrent) {
        source.copy(id = nextId, name = name)
    } else {
        Pattern(id = nextId, name = name, lengthSteps = source.lengthSteps)
    }
    return copy(patterns = patterns + pattern, currentPatternId = pattern.id, nextId = nextId + 1)
}

fun Project.selectPattern(patternId: Int): Project =
    if (patterns.any { it.id == patternId }) copy(currentPatternId = patternId) else this

fun Project.renamePattern(patternId: Int, name: String): Project =
    updatePattern(patternId) { it.copy(name = name.trim().ifEmpty { it.name }) }

fun Project.deletePattern(patternId: Int): Project {
    if (patterns.size <= 1) return this
    val index = patterns.indexOfFirst { it.id == patternId }
    if (index < 0) return this
    val remaining = patterns.filterNot { it.id == patternId }
    val current = if (currentPatternId == patternId) remaining[(index - 1).coerceAtLeast(0)].id else currentPatternId
    return copy(patterns = remaining, currentPatternId = current)
}

// ---- Channels ------------------------------------------------------------

fun Project.addChannel(name: String, instrument: Instrument): Project {
    if (channels.size >= MAX_CHANNELS) return this
    val channel = Channel(id = nextId, name = name, instrument = instrument, color = channels.size % CHANNEL_COLOR_COUNT)
    return copy(channels = channels + channel, nextId = nextId + 1)
}

fun Project.removeChannel(channelId: Int): Project = copy(
    channels = channels.filterNot { it.id == channelId },
    patterns = patterns.map { it.copy(notes = it.notes - channelId) },
)

fun Project.updateChannel(channelId: Int, transform: (Channel) -> Channel): Project =
    copy(channels = channels.map { if (it.id == channelId) transform(it) else it })

fun Project.moveChannel(channelId: Int, toIndex: Int): Project {
    val from = channels.indexOfFirst { it.id == channelId }
    if (from < 0) return this
    val list = channels.toMutableList()
    val channel = list.removeAt(from)
    list.add(toIndex.coerceIn(0, list.size), channel)
    return copy(channels = list)
}

// ---- Project settings ----------------------------------------------------

fun Project.withTempo(bpm: Float): Project = copy(tempo = bpm.coerceIn(Timing.MIN_TEMPO, Timing.MAX_TEMPO))

fun Project.withSwing(amount: Float): Project = copy(swing = amount.coerceIn(0f, 1f))
