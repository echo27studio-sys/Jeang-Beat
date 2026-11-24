package com.jeangbeat.core.audio

import com.jeangbeat.core.model.Clip
import com.jeangbeat.core.model.Project
import com.jeangbeat.core.model.RepeatRate
import com.jeangbeat.core.model.Timing

/** FL Studio-style transport mode: loop the current pattern, or play the Playlist. */
enum class PlayMode { PATTERN, SONG }

/** Number of mixer tracks the engine meters (Master + inserts). */
const val ENGINE_MIXER_TRACKS = 25

/**
 * Kotlin entry point to the native audio engine.
 *
 * All methods are thread-safe (calls are serialised) and cheap, so calling
 * them from the main thread is fine. Create with [realtime] = false for
 * offline rendering (export): no audio stream is opened.
 */
class AudioEngine(private val realtime: Boolean = true) : AutoCloseable {
    private val native = NativeEngine()
    private val lock = Any()
    private var ptr: Long = native.nativeCreate()
    private var channelIndex: Map<Int, Int> = emptyMap()
    private var lastTempo = Float.NaN
    private var lastSwing = Float.NaN
    private val loadedSamples = HashSet<Int>()

    /** Opens the low-latency audio output. Returns false if no output could be opened. */
    fun start(): Boolean = synchronized(lock) { realtime && ptr != 0L && native.nativeStartStream(ptr) }

    /** Closes the audio output (e.g. when the app goes to the background). */
    fun pause() = synchronized(lock) { if (ptr != 0L) native.nativeStopStream(ptr) }

    val sampleRate: Int get() = synchronized(lock) { if (ptr != 0L) native.nativeSampleRate(ptr) else 0 }

    /** Estimated output latency in milliseconds, or null if unknown. */
    val latencyMillis: Double? get() = synchronized(lock) {
        if (ptr == 0L) null else native.nativeLatencyMillis(ptr).takeIf { it >= 0 }
    }

    val isPlaying: Boolean get() = synchronized(lock) { ptr != 0L && native.nativeIsPlaying(ptr) }

    /** Current position in ticks: inside the pattern (PAT) or in the song (SONG). */
    val positionTicks: Float get() = synchronized(lock) { if (ptr != 0L) native.nativePositionTicks(ptr) else 0f }

    /** Highest output level since the last call, 0..1. */
    fun takePeak(): Float = synchronized(lock) { if (ptr != 0L) native.nativeTakePeak(ptr) else 0f }

    /** Fills [out] (size 2 × [ENGINE_MIXER_TRACKS]) with L/R peaks per mixer track since the last call. */
    fun takeMeters(out: FloatArray) = call { native.nativeTakeMeters(it, out) }

    fun play() = call { native.nativePlay(it) }
    fun stop() = call { native.nativeStop(it) }

    /** Moves the song marker; while playing in SONG mode playback jumps there. */
    fun setPosition(tick: Int) = call { native.nativeSetPosition(it, tick.toLong()) }
    fun allNotesOff() = call { native.nativeAllNotesOff(it) }
    fun setMasterGain(gain: Float) = call { native.nativeSetMasterGain(it, gain) }
    fun setRepeat(rate: RepeatRate) = call { native.nativeSetRepeatTicks(it, rate.ticks) }

    fun noteOn(channelId: Int, pitch: Int, velocity: Int) = withChannel(channelId) { p, ch -> native.nativeNoteOn(p, ch, pitch, velocity) }
    fun noteOff(channelId: Int, pitch: Int) = withChannel(channelId) { p, ch -> native.nativeNoteOff(p, ch, pitch) }
    fun padDown(channelId: Int, pitch: Int, velocity: Int) = withChannel(channelId) { p, ch -> native.nativePadHold(p, ch, pitch, velocity) }
    fun padUp(channelId: Int, pitch: Int) = withChannel(channelId) { p, ch -> native.nativePadRelease(p, ch, pitch) }

    // ---- Samples ----------------------------------------------------------------

    fun isSampleLoaded(id: Int): Boolean = synchronized(lock) { id in loadedSamples }

    /** Makes decoded audio (interleaved, 1 or 2 channels) available to sampler channels and audio clips. */
    fun loadSample(id: Int, data: FloatArray, channels: Int, sampleRate: Int) = call {
        native.nativeLoadSample(it, id, data, channels, sampleRate.toFloat())
        loadedSamples += id
    }

    fun unloadSample(id: Int) = call {
        native.nativeUnloadSample(it, id)
        loadedSamples -= id
    }

    // ---- Project ------------------------------------------------------------------

    /**
     * Sends the whole project (channels, patterns, playlist, mixer, automation)
     * to the engine. Safe to call on every edit: the engine swaps the new state
     * in between audio blocks without glitches, and effect tails carry on.
     */
    fun load(project: Project, mode: PlayMode) = synchronized(lock) {
        val p = ptr
        if (p == 0L) return@synchronized
        if (project.tempo != lastTempo) {
            native.nativeSetTempo(p, project.tempo)
            lastTempo = project.tempo
        }
        if (project.swing != lastSwing) {
            native.nativeSetSwing(p, project.swing)
            lastSwing = project.swing
        }
        val song = project.song
        val patternIndex = project.patterns.withIndex().associate { (i, pat) -> pat.id to i }
        val current = patternIndex[project.currentPattern.id] ?: 0
        native.nativeBeginSnapshot(
            p, mode.ordinal, current, song.lengthTicks.toLong(),
            if (song.hasLoop) song.loopStart.toLong() else -1L,
            if (song.hasLoop) song.loopEnd.toLong() else -1L,
        )

        project.channels.forEachIndexed { index, ch ->
            val i = native.nativeAddChannel(
                p, EngineEncoding.type(ch.instrument), EngineEncoding.sound(ch.instrument), EngineEncoding.sampleId(ch.instrument),
                EngineEncoding.params(ch.instrument), ch.volume, ch.pan, ch.muted, ch.solo, ch.mixerTrack,
            )
            check(i == index) { "Engine channel order mismatch" }
        }
        val index = EngineEncoding.channelIndex(project.channels)

        project.patterns.forEachIndexed { pi, pattern ->
            native.nativeAddPattern(p, pattern.lengthTicks, pattern.stepTicks)
            project.channels.forEachIndexed { ci, ch ->
                val notes = pattern.notesFor(ch.id)
                if (notes.isNotEmpty()) {
                    val (ints, floats) = EngineEncoding.notes(notes)
                    native.nativeSetPatternNotes(p, pi, ci, ints, floats)
                }
            }
        }

        for (clip in EngineEncoding.audibleClips(song)) {
            when (clip) {
                is Clip.PatternClip -> patternIndex[clip.patternId]?.let {
                    native.nativeAddPatternClip(p, clip.startTick.toLong(), clip.lengthTicks.toLong(), clip.offsetTicks.toLong(), it)
                }
                is Clip.AudioClip -> if (clip.sampleId in loadedSamples) {
                    native.nativeAddAudioClip(
                        p, clip.startTick.toLong(), clip.lengthTicks.toLong(), clip.offsetTicks.toLong(),
                        clip.sampleId, clip.gain, clip.mixerTrack,
                    )
                }
                is Clip.AutomationClip -> EngineEncoding.target(project, clip.target, index)?.let { t ->
                    if (clip.points.isNotEmpty()) {
                        native.nativeAddAutomation(
                            p, t.type, t.a, t.b, t.c, t.min, t.max, t.log,
                            clip.startTick.toLong(), clip.lengthTicks.toLong(),
                            clip.points.map { it.tick }.toIntArray(), clip.points.map { it.value }.toFloatArray(),
                        )
                    }
                }
            }
        }

        for (track in project.mixer.tracks) {
            native.nativeSetInsert(
                p, track.index, track.volume, track.pan, track.muted, track.solo, track.toMaster,
                track.sends.map { it.target }.toIntArray(), track.sends.map { it.level }.toFloatArray(),
            )
            for (slot in track.effects) {
                native.nativeAddEffect(
                    p, track.index, slot.id, slot.type.ordinal, slot.enabled, slot.mix,
                    slot.type.params.indices.map { slot.param(it) }.toFloatArray(),
                )
            }
        }

        native.nativeCommitSnapshot(p)
        channelIndex = index
    }

    // ---- Offline rendering (export) ------------------------------------------------

    /** Offline engines only: sets the render sample rate. */
    fun setRenderSampleRate(rate: Int) = call { native.nativeSetSampleRate(it, rate) }

    /** Offline engines only: output a single mixer insert (stems), or -1 for the full mix. */
    fun setStemInsert(insert: Int) = call { native.nativeSetStemInsert(it, insert) }

    /** Offline engines only: renders [frames] interleaved stereo frames into [out]. */
    fun render(out: FloatArray, frames: Int) = call {
        check(!realtime) { "render() is only for offline engines" }
        native.nativeRender(it, out, frames)
    }

    override fun close() = synchronized(lock) {
        if (ptr != 0L) {
            native.nativeDestroy(ptr)
            ptr = 0L
        }
    }

    private inline fun call(block: (Long) -> Unit) = synchronized(lock) { if (ptr != 0L) block(ptr) }

    private inline fun withChannel(channelId: Int, block: (Long, Int) -> Unit) = synchronized(lock) {
        val index = channelIndex[channelId] ?: return@synchronized
        if (ptr != 0L) block(ptr, index)
    }

    companion object {
        fun framesPerTick(sampleRate: Int, tempo: Float): Double = sampleRate * 60.0 / (tempo * Timing.TICKS_PER_BEAT)
    }
}
