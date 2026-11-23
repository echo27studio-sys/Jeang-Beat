package com.jeangbeat.core.audio

import com.jeangbeat.core.model.Project
import com.jeangbeat.core.model.RepeatRate

/**
 * Kotlin entry point to the native audio engine.
 *
 * All methods are thread-safe (calls are serialised); they are cheap and
 * non-blocking, so calling them from the main thread is fine.
 */
class AudioEngine : AutoCloseable {
    private val native = NativeEngine()
    private val lock = Any()
    private var ptr: Long = native.nativeCreate()
    private var channelIndex: Map<Int, Int> = emptyMap()
    private var lastTempo = Float.NaN
    private var lastSwing = Float.NaN

    /** Opens the low-latency audio output. Returns false if no output could be opened. */
    fun start(): Boolean = synchronized(lock) { ptr != 0L && native.nativeStartStream(ptr) }

    /** Closes the audio output (e.g. when the app goes to the background and is not playing). */
    fun pause() = synchronized(lock) { if (ptr != 0L) native.nativeStopStream(ptr) }

    val sampleRate: Int get() = synchronized(lock) { if (ptr != 0L) native.nativeSampleRate(ptr) else 0 }

    /** Estimated output latency in milliseconds, or null if unknown. */
    val latencyMillis: Double? get() = synchronized(lock) {
        if (ptr == 0L) null else native.nativeLatencyMillis(ptr).takeIf { it >= 0 }
    }

    val isPlaying: Boolean get() = synchronized(lock) { ptr != 0L && native.nativeIsPlaying(ptr) }

    /** Current position within the playing pattern, in ticks. */
    val positionTicks: Float get() = synchronized(lock) { if (ptr != 0L) native.nativePositionTicks(ptr) else 0f }

    /** Highest output level since the last call, 0..1. */
    fun takePeak(): Float = synchronized(lock) { if (ptr != 0L) native.nativeTakePeak(ptr) else 0f }

    fun play() = call { native.nativePlay(it) }
    fun stop() = call { native.nativeStop(it) }
    fun allNotesOff() = call { native.nativeAllNotesOff(it) }
    fun setMasterGain(gain: Float) = call { native.nativeSetMasterGain(it, gain) }
    fun setRepeat(rate: RepeatRate) = call { native.nativeSetRepeatTicks(it, rate.ticks) }

    fun noteOn(channelId: Int, pitch: Int, velocity: Int) = withChannel(channelId) { p, ch ->
        native.nativeNoteOn(p, ch, pitch, velocity)
    }

    fun noteOff(channelId: Int, pitch: Int) = withChannel(channelId) { p, ch -> native.nativeNoteOff(p, ch, pitch) }

    /** Pad pressed: plays now and keeps re-triggering while held if note repeat is on. */
    fun padDown(channelId: Int, pitch: Int, velocity: Int) = withChannel(channelId) { p, ch ->
        native.nativePadHold(p, ch, pitch, velocity)
    }

    fun padUp(channelId: Int, pitch: Int) = withChannel(channelId) { p, ch -> native.nativePadRelease(p, ch, pitch) }

    /**
     * Sends the project's current pattern, channels, tempo and swing to the
     * engine. Safe to call on every edit: the engine swaps the new state in
     * between audio blocks without glitches.
     */
    fun load(project: Project) = synchronized(lock) {
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
        val pattern = project.currentPattern
        native.nativeBeginSnapshot(p, pattern.lengthTicks)
        project.channels.forEachIndexed { index, channel ->
            val i = native.nativeAddChannel(
                p,
                EngineEncoding.type(channel.instrument),
                EngineEncoding.sound(channel.instrument),
                EngineEncoding.params(channel.instrument),
                channel.volume,
                channel.pan,
                channel.muted,
                channel.solo,
            )
            check(i == index) { "Engine channel order mismatch" }
            native.nativeSetChannelNotes(p, i, EngineEncoding.notes(pattern.notesFor(channel.id)))
        }
        native.nativeCommitSnapshot(p)
        channelIndex = EngineEncoding.channelIndex(project.channels)
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
}
