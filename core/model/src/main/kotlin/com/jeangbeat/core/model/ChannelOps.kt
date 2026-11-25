package com.jeangbeat.core.model

/* Channel-rack editing (3.0): clone, colour, clear everywhere, fill, shift, randomize. */

/**
 * Copies a channel (instrument, settings and its notes in every pattern) and
 * places the copy right below the original, on a free mixer insert.
 */
fun Project.cloneChannel(channelId: Int, name: String): Project {
    val source = channel(channelId) ?: return this
    if (channels.size >= MAX_CHANNELS) return this
    val id = nextId
    val copy = source.copy(id = id, name = name, solo = false)
    val index = channels.indexOfFirst { it.id == channelId }
    val list = channels.toMutableList().apply { add(index + 1, copy) }
    return copy(
        channels = list,
        patterns = patterns.map { p ->
            val notes = p.notesFor(channelId)
            if (notes.isEmpty()) p else p.copy(notes = p.notes + (id to notes))
        },
        nextId = nextId + 1,
    )
}

fun Project.setChannelColor(channelId: Int, color: Int): Project =
    updateChannel(channelId) { it.copy(color = Math.floorMod(color, CHANNEL_COLOR_COUNT)) }

/** Removes the channel's notes from every pattern. */
fun Project.clearChannelEverywhere(channelId: Int): Project =
    copy(patterns = patterns.map { it.copy(notes = it.notes - channelId) })

/** FL Studio "Fill each N steps": puts a step on 0, N, 2N… (keeps off-grid notes). */
fun Project.fillEachSteps(channelId: Int, every: Int): Project {
    if (every <= 0) return this
    val steps = (0 until currentPattern.lengthSteps step every).toList()
    return setChannelSteps(channelId, steps)
}

/** Shifts the channel's notes by whole steps, wrapping round the pattern. */
fun Project.shiftChannelSteps(channelId: Int, steps: Int): Project = rotateChannel(channelId, steps)

/** Random velocity variation (±[amount] of full range) for one channel in the current pattern. */
fun Project.randomizeVelocity(channelId: Int, amount: Float, seed: Int): Project =
    editNotes(channelId) { it.humanized(amount, seed) }

/** Moves a channel up (-1) or down (+1) in the rack. */
fun Project.moveChannelBy(channelId: Int, delta: Int): Project {
    val index = channels.indexOfFirst { it.id == channelId }
    if (index < 0) return this
    return moveChannel(channelId, (index + delta).coerceIn(0, channels.lastIndex))
}

/** Replaces a channel's instrument, keeping its name, notes, colour and routing. */
fun Project.replaceInstrument(channelId: Int, instrument: Instrument): Project =
    updateChannel(channelId) { it.copy(instrument = instrument) }

/**
 * FL Studio "Split by channel": makes one pattern per channel that has notes in
 * [patternId] ("<Pattern> – <Channel>"). Every Playlist clip of the original is
 * replaced by the per-channel clips on consecutive tracks (tracks are added if
 * needed). The original pattern is kept. Returns this when there is nothing to split.
 */
fun Project.splitByChannel(patternId: Int): Project {
    val source = pattern(patternId) ?: return this
    val used = channels.filter { source.notesFor(it.id).isNotEmpty() }
    if (used.size < 2) return this
    var p = this
    var id = nextId
    val parts = used.map { ch ->
        Pattern(
            id = id++, name = "${source.name} – ${ch.name}", lengthSteps = source.lengthSteps, stepTicks = source.stepTicks,
            notes = mapOf(ch.id to source.notesFor(ch.id)), color = ch.color,
        )
    }
    p = p.copy(patterns = p.patterns + parts, nextId = id)
    val originals = p.song.clips.filterIsInstance<Clip.PatternClip>().filter { it.patternId == patternId }
    for (clip in originals) {
        val first = p.song.tracks.indexOfFirst { it.id == clip.trackId }.coerceAtLeast(0)
        while (p.song.tracks.size < first + parts.size && p.song.tracks.size < Song.MAX_TRACKS) p = p.addPlaylistTrack()
        var clipId = p.nextId
        val replacements = parts.mapIndexed { k, part ->
            val track = p.song.tracks[(first + k).coerceAtMost(p.song.tracks.lastIndex)]
            clip.copy(id = clipId++, trackId = track.id, patternId = part.id)
        }
        p = p.copy(song = p.song.copy(clips = p.song.clips.filterNot { it.id == clip.id } + replacements), nextId = clipId)
    }
    return p.copy(currentPatternId = parts.first().id)
}
