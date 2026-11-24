package com.jeangbeat.core.model

/* Mixer editing. All pure, like ProjectOps. */

fun Project.updateMixerTrack(index: Int, transform: (MixerTrack) -> MixerTrack): Project =
    copy(mixer = mixer.copy(tracks = mixer.tracks.map { if (it.index == index) transform(it) else it }))

fun Project.addEffect(trackIndex: Int, type: EffectType): Project {
    val track = mixer.track(trackIndex) ?: return this
    if (track.effects.size >= EFFECT_SLOTS) return this
    return updateMixerTrack(trackIndex) { it.copy(effects = it.effects + EffectSlot(nextId, type)) }.copy(nextId = nextId + 1)
}

fun Project.removeEffect(trackIndex: Int, slotId: Int): Project = updateMixerTrack(trackIndex) { t ->
    t.copy(effects = t.effects.filterNot { it.id == slotId })
}.let { p ->
    // Automation on a removed effect has nothing to control.
    p.updateSong { s ->
        s.copy(clips = s.clips.filterNot { it is Clip.AutomationClip && (it.target as? AutomationTarget.EffectParam)?.slotId == slotId })
    }
}

fun Project.updateEffect(trackIndex: Int, slotId: Int, transform: (EffectSlot) -> EffectSlot): Project =
    updateMixerTrack(trackIndex) { t -> t.copy(effects = t.effects.map { if (it.id == slotId) transform(it) else it }) }

fun Project.setEffectParam(trackIndex: Int, slotId: Int, param: Int, value: Float): Project =
    updateEffect(trackIndex, slotId) { slot ->
        val spec = slot.type.params.getOrNull(param) ?: return@updateEffect slot
        val list = slot.type.params.indices.map { slot.param(it) }.toMutableList()
        list[param] = spec.clamp(value)
        slot.copy(params = list)
    }

fun Project.moveEffect(trackIndex: Int, slotId: Int, toPosition: Int): Project = updateMixerTrack(trackIndex) { t ->
    val list = t.effects.toMutableList()
    val from = list.indexOfFirst { it.id == slotId }
    if (from < 0) return@updateMixerTrack t
    val slot = list.removeAt(from)
    list.add(toPosition.coerceIn(0, list.size), slot)
    t.copy(effects = list)
}

/**
 * Sets a send level (0 removes the send). Sends that would create a loop, or
 * that target Master or the track itself, are refused (returns the project unchanged).
 */
fun Project.setSend(from: Int, to: Int, level: Float): Project {
    if (from == to || to == 0 || from == 0) return this
    if (mixer.track(from) == null || mixer.track(to) == null) return this
    val l = level.coerceIn(0f, 1f)
    if (l > 0f && wouldCreateCycle(from, to)) return this
    return updateMixerTrack(from) { t ->
        val others = t.sends.filterNot { it.target == to }
        t.copy(sends = if (l > 0f) others + Send(to, l) else others)
    }
}

/** True if routing [from] into [to] would make audio flow in a circle. Includes sidechain links. */
fun Project.wouldCreateCycle(from: Int, to: Int): Boolean {
    // Is there already a path from `to` back to `from`?
    val seen = HashSet<Int>()
    val stack = ArrayDeque(listOf(to))
    while (stack.isNotEmpty()) {
        val n = stack.removeLast()
        if (n == from) return true
        if (!seen.add(n)) continue
        stack.addAll(mixerEdges(n))
    }
    return false
}

/** Tracks that [index] feeds: its sends plus any compressor that uses it as sidechain input. */
fun Project.mixerEdges(index: Int): List<Int> {
    val track = mixer.track(index) ?: return emptyList()
    val sidechained = mixer.tracks.filter { t -> t.effects.any { it.sidechainSource() == index } }.map { it.index }
    return track.sends.map { it.target } + sidechained
}

/** Sidechain source insert for a compressor slot, or null. */
fun EffectSlot.sidechainSource(): Int? =
    if (type == EffectType.COMPRESSOR && enabled) param(5).toInt().takeIf { it > 0 } else null

/** Sets a compressor's sidechain input, refusing loops. Use -1 for none. */
fun Project.setSidechain(trackIndex: Int, slotId: Int, source: Int): Project {
    if (source > 0 && (source == trackIndex || wouldCreateCycle(source, trackIndex))) return this
    return setEffectParam(trackIndex, slotId, 5, source.toFloat())
}

/** Channels routed to [insert]. */
fun Project.channelsOn(insert: Int): List<Channel> = channels.filter { it.mixerTrack == insert }
