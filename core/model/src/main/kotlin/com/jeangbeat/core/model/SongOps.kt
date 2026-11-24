package com.jeangbeat.core.model

/* Playlist (song arrangement) editing. All pure, like ProjectOps. */

fun Project.updateSong(transform: (Song) -> Song): Project = copy(song = transform(song))

fun Project.updateClip(clipId: Int, transform: (Clip) -> Clip): Project =
    updateSong { s -> s.copy(clips = s.clips.map { if (it.id == clipId) transform(it) else it }) }

private fun Clip.moved(start: Int, trackId: Int): Clip = when (this) {
    is Clip.PatternClip -> copy(startTick = start, trackId = trackId)
    is Clip.AudioClip -> copy(startTick = start, trackId = trackId)
    is Clip.AutomationClip -> copy(startTick = start, trackId = trackId)
}

private fun Clip.withLength(length: Int): Clip = when (this) {
    is Clip.PatternClip -> copy(lengthTicks = length)
    is Clip.AudioClip -> copy(lengthTicks = length)
    is Clip.AutomationClip -> copy(lengthTicks = length, points = points.filter { it.tick <= length }.ifEmpty { points.take(1) })
}

fun Clip.withMuted(muted: Boolean): Clip = when (this) {
    is Clip.PatternClip -> copy(muted = muted)
    is Clip.AudioClip -> copy(muted = muted)
    is Clip.AutomationClip -> copy(muted = muted)
}

// ---- Tracks ----------------------------------------------------------------

fun Project.addPlaylistTrack(): Project {
    if (song.tracks.size >= Song.MAX_TRACKS) return this
    val id = (song.tracks.maxOfOrNull { it.id } ?: 0) + 1
    return updateSong { it.copy(tracks = it.tracks + PlaylistTrack(id, "Track $id")) }
}

fun Project.updatePlaylistTrack(trackId: Int, transform: (PlaylistTrack) -> PlaylistTrack): Project =
    updateSong { s -> s.copy(tracks = s.tracks.map { if (it.id == trackId) transform(it) else it }) }

/** Removes a track and every clip on it. */
fun Project.removePlaylistTrack(trackId: Int): Project = updateSong { s ->
    if (s.tracks.size <= 1) s else s.copy(tracks = s.tracks.filterNot { it.id == trackId }, clips = s.clips.filterNot { it.trackId == trackId })
}

// ---- Clips -----------------------------------------------------------------

/** Places [patternId] at [startTick]; the clip is as long as the pattern unless [lengthTicks] is given. */
fun Project.addPatternClip(trackId: Int, startTick: Int, patternId: Int, lengthTicks: Int? = null): Project {
    val pattern = pattern(patternId) ?: return this
    val clip = Clip.PatternClip(
        id = nextId, trackId = trackId, startTick = startTick.coerceAtLeast(0),
        lengthTicks = (lengthTicks ?: pattern.lengthTicks).coerceAtLeast(1), patternId = patternId,
    )
    return copy(song = song.copy(clips = song.clips + clip), nextId = nextId + 1)
}

fun Project.addAudioClip(trackId: Int, startTick: Int, sampleId: Int, lengthTicks: Int, mixerTrack: Int = 0): Project {
    if (sample(sampleId) == null) return this
    val clip = Clip.AudioClip(nextId, trackId, startTick.coerceAtLeast(0), lengthTicks.coerceAtLeast(1), sampleId, mixerTrack = mixerTrack)
    return copy(song = song.copy(clips = song.clips + clip), nextId = nextId + 1)
}

fun Project.addAutomationClip(trackId: Int, startTick: Int, lengthTicks: Int, target: AutomationTarget, initial: Float = 0.5f): Project {
    val len = lengthTicks.coerceAtLeast(1)
    val clip = Clip.AutomationClip(
        nextId, trackId, startTick.coerceAtLeast(0), len, target,
        points = listOf(AutoPoint(0, initial), AutoPoint(len, initial)),
    )
    return copy(song = song.copy(clips = song.clips + clip), nextId = nextId + 1)
}

fun Project.moveClip(clipId: Int, startTick: Int, trackId: Int): Project =
    updateClip(clipId) { it.moved(startTick.coerceAtLeast(0), trackId) }

fun Project.resizeClip(clipId: Int, lengthTicks: Int): Project =
    updateClip(clipId) { it.withLength(lengthTicks.coerceAtLeast(1)) }

fun Project.deleteClip(clipId: Int): Project = updateSong { s -> s.copy(clips = s.clips.filterNot { it.id == clipId }) }

fun Project.toggleClipMute(clipId: Int): Project = updateClip(clipId) { it.withMuted(!it.muted) }

/** Duplicates a clip right after itself on the same track. */
fun Project.duplicateClip(clipId: Int): Project {
    val c = song.clip(clipId) ?: return this
    val copy = when (c) {
        is Clip.PatternClip -> c.copy(id = nextId, startTick = c.endTick)
        is Clip.AudioClip -> c.copy(id = nextId, startTick = c.endTick)
        is Clip.AutomationClip -> c.copy(id = nextId, startTick = c.endTick)
    }
    return copy(song = song.copy(clips = song.clips + copy), nextId = nextId + 1)
}

/** Cuts a clip in two at song position [atTick] (FL Studio slice tool). */
fun Project.sliceClip(clipId: Int, atTick: Int): Project {
    val c = song.clip(clipId) ?: return this
    if (atTick <= c.startTick || atTick >= c.endTick) return this
    val leftLen = atTick - c.startTick
    val rightLen = c.endTick - atTick
    val (left, right) = when (c) {
        is Clip.PatternClip -> c.copy(lengthTicks = leftLen) to
            c.copy(id = nextId, startTick = atTick, lengthTicks = rightLen, offsetTicks = c.offsetTicks + leftLen)
        is Clip.AudioClip -> c.copy(lengthTicks = leftLen) to
            c.copy(id = nextId, startTick = atTick, lengthTicks = rightLen, offsetTicks = c.offsetTicks + leftLen)
        is Clip.AutomationClip -> {
            val v = c.valueAt(leftLen)
            c.copy(lengthTicks = leftLen, points = c.points.filter { it.tick < leftLen } + AutoPoint(leftLen, v)) to
                c.copy(
                    id = nextId, startTick = atTick, lengthTicks = rightLen,
                    points = listOf(AutoPoint(0, v)) + c.points.filter { it.tick > leftLen }.map { it.copy(tick = it.tick - leftLen) },
                )
        }
    }
    return copy(song = song.copy(clips = song.clips.map { if (it.id == clipId) left else it } + right), nextId = nextId + 1)
}

/**
 * FL Studio "make unique": gives a pattern clip its own copy of the pattern,
 * so editing it no longer changes the other clips.
 */
fun Project.makeClipUnique(clipId: Int, newName: String): Project {
    val c = song.clip(clipId) as? Clip.PatternClip ?: return this
    val source = pattern(c.patternId) ?: return this
    val copyPattern = source.copy(id = nextId, name = newName, color = (source.color + 1) % CHANNEL_COLOR_COUNT)
    return copy(
        patterns = patterns + copyPattern,
        song = song.copy(clips = song.clips.map { if (it.id == clipId) c.copy(patternId = copyPattern.id) else it }),
        nextId = nextId + 1,
    )
}

fun Project.setAutomationPoints(clipId: Int, points: List<AutoPoint>): Project = updateClip(clipId) { c ->
    if (c !is Clip.AutomationClip) c else c.copy(
        points = points.map { AutoPoint(it.tick.coerceIn(0, c.lengthTicks), it.value.coerceIn(0f, 1f)) }.sortedBy { it.tick }.ifEmpty { c.points },
    )
}

fun Project.setLoop(startTick: Int, endTick: Int): Project = updateSong {
    if (endTick > startTick && startTick >= 0) it.copy(loopStart = startTick, loopEnd = endTick) else it.copy(loopStart = -1, loopEnd = -1)
}

fun Project.clearLoop(): Project = updateSong { it.copy(loopStart = -1, loopEnd = -1) }

/** Linear interpolation of an automation clip at [tick] (relative to the clip start). */
fun Clip.AutomationClip.valueAt(tick: Int): Float {
    if (points.isEmpty()) return 0.5f
    if (tick <= points.first().tick) return points.first().value
    if (tick >= points.last().tick) return points.last().value
    val i = points.indexOfLast { it.tick <= tick }
    val a = points[i]
    val b = points[i + 1]
    if (b.tick == a.tick) return b.value
    return a.value + (b.value - a.value) * (tick - a.tick) / (b.tick - a.tick).toFloat()
}

/** Clips that use pattern [patternId]. */
fun Song.clipsOf(patternId: Int): List<Clip.PatternClip> = clips.filterIsInstance<Clip.PatternClip>().filter { it.patternId == patternId }
