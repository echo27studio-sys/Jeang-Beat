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

private val noteOrder = compareBy<Note>({ it.startTick }, { it.pitch })

/**
 * Clamps notes into the pattern (FIXED) or into the 512-bar limit (AUTO, where the
 * pattern then grows to fit) and removes duplicates (same start and pitch; last wins).
 */
fun Pattern.normalized(notes: List<Note>): List<Note> {
    val byKey = LinkedHashMap<Long, Note>()
    val limit = maxTicks
    for (n in notes) {
        val start = n.startTick.coerceIn(0, limit - 1)
        val clean = n.copy(
            startTick = start,
            lengthTicks = n.lengthTicks.coerceIn(1, limit - start),
            pitch = n.pitch.coerceIn(0, 127),
            velocity = n.velocity.coerceIn(1, 127),
            pan = n.pan.coerceIn(-1f, 1f),
            finePitch = n.finePitch.coerceIn(-100f, 100f),
            release = n.release.coerceIn(0f, 1f),
        )
        byKey[clean.startTick.toLong() * 128 + clean.pitch] = clean
    }
    return byKey.values.sortedWith(noteOrder)
}

/** General note edit for one channel in the current pattern (the piano roll uses this). */
fun Project.editNotes(channelId: Int, transform: (List<Note>) -> List<Note>): Project {
    if (channel(channelId) == null) return this
    return updateCurrentPattern { pattern -> pattern.withNotes(channelId, pattern.normalized(transform(pattern.notesFor(channelId)))) }
}

// ---- Step sequencer ------------------------------------------------------

/** True when a note starts exactly on [step] for the channel. */
fun Pattern.isStepOn(channelId: Int, step: Int): Boolean {
    val tick = stepTick(step)
    return notesFor(channelId).any { it.startTick == tick }
}

fun Pattern.stepNote(channelId: Int, step: Int): Note? {
    val tick = stepTick(step)
    return notesFor(channelId).firstOrNull { it.startTick == tick }
}

fun Pattern.stepVelocity(channelId: Int, step: Int): Int? = stepNote(channelId, step)?.velocity

/** True when all the channel's notes sit on the step grid (so the rack can show step buttons). */
fun Pattern.isStepChannel(channelId: Int, instrument: Instrument): Boolean =
    !instrument.isMelodic && notesFor(channelId).all { it.startTick % stepTicks == 0 }

fun Project.toggleStep(channelId: Int, step: Int, velocity: Int = Note.DEFAULT_VELOCITY): Project {
    val channel = channel(channelId) ?: return this
    val pattern0 = currentPattern
    // AUTO patterns accept steps past the end (the rack shows one extra bar) and grow.
    val maxStep = if (pattern0.lengthMode == LengthMode.AUTO) pattern0.maxTicks / pattern0.stepTicks else pattern0.stepCount
    if (step !in 0 until maxStep) return this
    return updateCurrentPattern { pattern ->
        val tick = pattern.stepTick(step)
        val existing = pattern.notesFor(channelId)
        val updated = if (existing.any { it.startTick == tick }) {
            existing.filterNot { it.startTick == tick }
        } else {
            existing + Note(tick, pattern.stepTicks, channel.instrument.defaultPitch, velocity.coerceIn(1, 127))
        }
        pattern.withNotes(channelId, pattern.normalized(updated))
    }
}

/** Edits the note on a step (graph editor: velocity, pan, pitch). */
fun Project.updateStepNote(channelId: Int, step: Int, transform: (Note) -> Note): Project = updateCurrentPattern { pattern ->
    val tick = pattern.stepTick(step)
    pattern.withNotes(
        channelId,
        pattern.normalized(pattern.notesFor(channelId).map { if (it.startTick == tick) transform(it) else it }),
    )
}

fun Project.setStepVelocity(channelId: Int, step: Int, velocity: Int): Project =
    updateStepNote(channelId, step) { it.copy(velocity = velocity.coerceIn(1, 127)) }

// ---- Notes (piano roll) --------------------------------------------------

/** Adds a note, replacing any note with the same start and pitch. */
fun Project.addNote(channelId: Int, note: Note): Project = editNotes(channelId) { it + note }

fun Project.removeNote(channelId: Int, note: Note): Project = editNotes(channelId) { it - note }

/** Replaces [old] with [new] (used for move, resize and property edits). */
fun Project.replaceNote(channelId: Int, old: Note, new: Note): Project =
    editNotes(channelId) { notes -> notes.filterNot { it == old } + new }

fun Project.clearChannel(channelId: Int): Project = updateCurrentPattern { it.withNotes(channelId, emptyList()) }

/** Records a live hit into the current pattern, quantized to [gridTicks]. Hits past the end wrap. */
fun Project.recordHit(channelId: Int, tick: Int, pitch: Int, velocity: Int, gridTicks: Int): Project {
    val pattern = currentPattern
    val start = Math.floorMod(Timing.quantize(tick, gridTicks), pattern.lengthTicks)
    return addNote(channelId, Note(start, gridTicks, pitch, velocity))
}

// ---- Patterns ------------------------------------------------------------

/** Sets a FIXED length in bars (1..512), keeping the meter. Notes past the end are dropped or trimmed. */
fun Project.setPatternBars(bars: Int): Project = updateCurrentPattern { pattern ->
    val steps = bars.coerceIn(1, MAX_PATTERN_BARS) * pattern.meter.stepsPerBar
    val lengthTicks = steps * pattern.stepTicks
    pattern.copy(
        lengthSteps = steps,
        lengthMode = LengthMode.FIXED,
        notes = pattern.notes
            .mapValues { (_, notes) ->
                notes.filter { it.startTick < lengthTicks }
                    .map { it.copy(lengthTicks = it.lengthTicks.coerceAtMost(lengthTicks - it.startTick)) }
            }
            .filterValues { it.isNotEmpty() },
    )
}

/** Switches the current pattern to AUTO length (grows with its notes, at least 1 bar). */
fun Project.setPatternAutoLength(): Project = updateCurrentPattern { pattern ->
    pattern.copy(lengthMode = LengthMode.AUTO, lengthSteps = pattern.meter.stepsPerBar)
}

/** Clips that follow an auto-length pattern take the pattern's current length. */
fun Project.syncClipLengths(): Project {
    var changed = false
    val clips = song.clips.map { c ->
        if (c is Clip.PatternClip && c.followsPattern) {
            val len = (pattern(c.patternId)?.lengthTicks ?: c.lengthTicks) - c.offsetTicks
            if (len > 0 && len != c.lengthTicks) {
                changed = true
                c.copy(lengthTicks = len)
            } else c
        } else c
    }
    return if (changed) copy(song = song.copy(clips = clips)) else this
}

/** Compatibility helper: length in 16th steps. */
fun Project.setPatternLength(steps: Int): Project = setPatternBars((steps / Meter.FOUR_FOUR.stepsPerBar).coerceAtLeast(1))

/** Switches between 4/4 (16th steps) and 12/8 (triplet steps). Bars and note times are kept. */
fun Project.setPatternMeter(meter: Meter): Project = updateCurrentPattern { pattern ->
    if (pattern.meter == meter) return@updateCurrentPattern pattern
    val bars = if (pattern.lengthMode == LengthMode.FIXED) pattern.bars else (pattern.lengthSteps * pattern.stepTicks / Timing.TICKS_PER_BAR).coerceAtLeast(1)
    pattern.copy(stepTicks = meter.stepTicks, lengthSteps = bars * meter.stepsPerBar)
}

fun Project.addPattern(name: String, copyCurrent: Boolean): Project {
    if (patterns.size >= MAX_PATTERNS) return this
    val source = currentPattern
    val color = patterns.size % CHANNEL_COLOR_COUNT
    val pattern = if (copyCurrent) {
        source.copy(id = nextId, name = name, color = color)
    } else {
        Pattern(id = nextId, name = name, lengthSteps = source.meter.stepsPerBar, stepTicks = source.stepTicks, color = color)
    }
    return copy(patterns = patterns + pattern, currentPatternId = pattern.id, nextId = nextId + 1)
}

fun Project.selectPattern(patternId: Int): Project =
    if (patterns.any { it.id == patternId }) copy(currentPatternId = patternId) else this

fun Project.renamePattern(patternId: Int, name: String): Project =
    updatePattern(patternId) { it.copy(name = name.trim().ifEmpty { it.name }) }

fun Project.setPatternColor(patternId: Int, color: Int): Project = updatePattern(patternId) { it.copy(color = color) }

/** Deletes a pattern and its playlist clips. The last pattern cannot be deleted. */
fun Project.deletePattern(patternId: Int): Project {
    if (patterns.size <= 1) return this
    val index = patterns.indexOfFirst { it.id == patternId }
    if (index < 0) return this
    val remaining = patterns.filterNot { it.id == patternId }
    val current = if (currentPatternId == patternId) remaining[(index - 1).coerceAtLeast(0)].id else currentPatternId
    return copy(
        patterns = remaining,
        currentPatternId = current,
        song = song.copy(clips = song.clips.filterNot { it is Clip.PatternClip && it.patternId == patternId }),
    )
}

// ---- Channels ------------------------------------------------------------

/**
 * First mixer insert that is still blank: no channel or audio clip uses it, it has
 * no effects, no sends point at it and it still has its default name. Master if none.
 */
fun Project.freeMixerInsert(): Int {
    val used = channels.map { it.mixerTrack }.toSet() +
        song.clips.filterIsInstance<Clip.AudioClip>().map { it.mixerTrack }
    val sendTargets = mixer.tracks.flatMap { t -> t.sends.map { it.target } }.toSet()
    return (1..MIXER_INSERTS).firstOrNull { i ->
        val t = mixer.track(i)
        i !in used && i !in sendTargets && t != null && t.effects.isEmpty() && t.name == "Insert $i"
    } ?: 0
}

/** Adds a channel on its own mixer insert, named after the channel. */
fun Project.addChannel(name: String, instrument: Instrument): Project {
    if (channels.size >= MAX_CHANNELS) return this
    val insert = freeMixerInsert()
    val channel = Channel(
        id = nextId, name = name, instrument = instrument,
        color = channels.size % CHANNEL_COLOR_COUNT, mixerTrack = insert,
    )
    val renamed = if (insert > 0) renameDefaultInsert(insert, name) else this
    return renamed.copy(channels = channels + channel, nextId = nextId + 1)
}

private fun Project.renameDefaultInsert(index: Int, name: String): Project =
    updateMixerTrack(index) { if (it.name == "Insert $index") it.copy(name = name) else it }

/** Removes a channel, its notes in every pattern and automation that targets it. */
fun Project.removeChannel(channelId: Int): Project = copy(
    channels = channels.filterNot { it.id == channelId },
    patterns = patterns.map { it.copy(notes = it.notes - channelId) },
    song = song.copy(
        clips = song.clips.filterNot { clip ->
            clip is Clip.AutomationClip && when (val t = clip.target) {
                is AutomationTarget.ChannelVolume -> t.channelId == channelId
                is AutomationTarget.ChannelPan -> t.channelId == channelId
                else -> false
            }
        },
    ),
)

fun Project.updateChannel(channelId: Int, transform: (Channel) -> Channel): Project =
    copy(channels = channels.map { if (it.id == channelId) transform(it) else it })

fun Project.setChannelMixerTrack(channelId: Int, insert: Int): Project =
    updateChannel(channelId) { it.copy(mixerTrack = insert.coerceIn(0, MIXER_INSERTS)) }

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

// ---- Samples -------------------------------------------------------------

fun Project.addSample(sample: SampleRef): Project = copy(samples = samples + sample.copy(id = nextId), nextId = nextId + 1)

/** Removes a sample, its audio clips and any sampler channels that play it. */
fun Project.removeSample(sampleId: Int): Project {
    val channelIds = channels.filter { (it.instrument as? Instrument.Sampler)?.sampleId == sampleId }.map { it.id }
    return channelIds.fold(this) { p, id -> p.removeChannel(id) }.let { p ->
        p.copy(
            samples = p.samples.filterNot { it.id == sampleId },
            song = p.song.copy(clips = p.song.clips.filterNot { it is Clip.AudioClip && it.sampleId == sampleId }),
        )
    }
}
