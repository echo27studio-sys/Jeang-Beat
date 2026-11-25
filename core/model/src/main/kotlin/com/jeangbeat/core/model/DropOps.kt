package com.jeangbeat.core.model

/* Browser drag & drop (3.0): pure functions that turn a drop into a project edit. */

/** What is being dropped: an audio file (already copied into the sample store) or a built-in sound. */
sealed interface DropItem {
    val name: String

    data class Sample(val ref: SampleRef) : DropItem {
        override val name: String get() = ref.name
    }

    data class Sound(override val name: String, val instrument: Instrument) : DropItem
}

/** Where it is dropped. */
sealed interface DropTarget {
    /** Empty area of the channel rack: a new channel. */
    data object NewChannel : DropTarget

    /** An existing channel row (or the piano roll of that channel): replace its sound. */
    data class Channel(val channelId: Int) : DropTarget

    /** A Playlist lane at [tick] (already snapped); [trackId] -1 means "a new track". */
    data class PlaylistLane(val trackId: Int, val tick: Int) : DropTarget

    /** A mixer insert strip: a new channel routed to that insert. */
    data class MixerInsert(val insert: Int) : DropTarget
}

/**
 * The project's sample for [ref]: an existing one with the same file (the sample
 * store names files by content hash, so equal files are shared) or a new one.
 * Returns the project and the sample id.
 */
fun Project.withSample(ref: SampleRef): Pair<Project, Int> {
    samples.firstOrNull { it.fileName == ref.fileName }?.let { return this to it.id }
    val id = nextId
    return addSample(ref) to id
}

/** The id [dropItem] will give this sample, so the engine can load it before the edit. */
fun Project.sampleIdFor(ref: SampleRef): Int = withSample(ref).second

/** Length of a sample in ticks at the project tempo (at least one tick). */
fun Project.sampleTicks(ref: SampleRef): Int =
    (ref.seconds * tempo / 60.0 * Timing.TICKS_PER_BEAT).toInt().coerceAtLeast(1)

/** Applies a drop. Returns this when the drop makes no sense (e.g. a synth sound on the Playlist). */
fun Project.dropItem(item: DropItem, target: DropTarget): Project {
    val (p, instrument) = when (item) {
        is DropItem.Sample -> {
            val (withRef, id) = withSample(item.ref)
            withRef to Instrument.Sampler(id)
        }
        is DropItem.Sound -> this to item.instrument
    }
    return when (target) {
        DropTarget.NewChannel -> p.addChannel(item.name, instrument).orUnchanged(this, p)
        is DropTarget.Channel -> {
            val ch = p.channel(target.channelId) ?: return this
            // Keep a sampler's tuning and envelope when only the sample changes.
            val replaced = when {
                ch.instrument is Instrument.Sampler && instrument is Instrument.Sampler -> ch.instrument.copy(sampleId = instrument.sampleId)
                else -> instrument
            }
            p.replaceInstrument(ch.id, replaced).updateChannel(ch.id) { it.copy(name = item.name) }
        }
        is DropTarget.MixerInsert -> {
            val added = p.addChannel(item.name, instrument)
            if (added.channels.size == p.channels.size) return this
            added.setChannelMixerTrack(added.channels.last().id, target.insert)
        }
        is DropTarget.PlaylistLane -> {
            if (item !is DropItem.Sample) return this
            var q = p
            var trackId = target.trackId
            if (q.song.tracks.none { it.id == trackId }) {
                val before = q.song.tracks.size
                q = q.addPlaylistTrack()
                if (q.song.tracks.size == before) return this
                trackId = q.song.tracks.last().id
            }
            val sampleId = q.samples.first { it.fileName == item.ref.fileName }.id
            q.addAudioClip(trackId, target.tick.coerceAtLeast(0), sampleId, q.sampleTicks(item.ref))
        }
    }
}

/** A failed add (rack full) leaves the project untouched, without the orphan sample. */
private fun Project.orUnchanged(original: Project, before: Project): Project =
    if (channels.size == before.channels.size) original else this
