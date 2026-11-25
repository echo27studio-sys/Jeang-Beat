package com.jeangbeat.core.audio

import com.jeangbeat.core.model.Project
import com.jeangbeat.core.model.SampleRef
import com.jeangbeat.core.model.clearLoop
import kotlin.math.floor

/** Decoded sample for the engine: interleaved floats. */
class SampleData(val samples: FloatArray, val channels: Int, val sampleRate: Int)

/**
 * Renders a project offline (faster than real time) for export.
 * Uses its own engine instance so the app's playback is not disturbed.
 */
class OfflineRenderer(
    private val project: Project,
    private val loadSample: (SampleRef) -> SampleData?,
) {
    /** Which part to render: the whole song, the current pattern, or the song's loop region. */
    enum class Range { SONG, PATTERN, LOOP }

    private fun ticks(range: Range): Int = when (range) {
        Range.SONG -> project.song.lengthTicks
        Range.PATTERN -> project.currentPattern.lengthTicks
        Range.LOOP -> if (project.song.hasLoop) project.song.loopEnd - project.song.loopStart else project.song.lengthTicks
    }

    /** Frames of music (rounded down so playback never wraps back to the start). */
    fun musicFrames(range: Range, sampleRate: Int): Long =
        floor(ticks(range) * AudioEngine.framesPerTick(sampleRate, project.tempo)).toLong()

    /** Total frames: the music plus a reverb/delay tail. */
    fun totalFrames(range: Range, sampleRate: Int, tailSeconds: Double): Long =
        musicFrames(range, sampleRate) + (tailSeconds * sampleRate).toLong()

    fun musicFrames(mode: PlayMode, sampleRate: Int): Long = musicFrames(mode.range(), sampleRate)

    fun totalFrames(mode: PlayMode, sampleRate: Int, tailSeconds: Double): Long = totalFrames(mode.range(), sampleRate, tailSeconds)

    private fun PlayMode.range() = if (this == PlayMode.SONG) Range.SONG else Range.PATTERN

    /** Loudest sample of the mix (for normalizing), rendered silently. */
    fun peak(range: Range, sampleRate: Int, tailSeconds: Double, isCancelled: () -> Boolean = { false }, onProgress: (Float) -> Unit = {}): Float {
        var peak = 0f
        render(range, sampleRate, -1, tailSeconds, 1f, isCancelled, onProgress) { buf, n ->
            for (i in 0 until n * 2) peak = maxOf(peak, kotlin.math.abs(buf[i]))
        }
        return peak
    }

    /**
     * Renders the mix (or one mixer insert when [stemInsert] >= 0) and hands
     * each chunk to [onChunk] as interleaved stereo floats. [onProgress] gets 0..1.
     * Returns false if [isCancelled] stopped it.
     */
    fun render(
        mode: PlayMode,
        sampleRate: Int = 48_000,
        stemInsert: Int = -1,
        tailSeconds: Double = 2.0,
        isCancelled: () -> Boolean = { false },
        onProgress: (Float) -> Unit = {},
        onChunk: (FloatArray, Int) -> Unit,
    ): Boolean = render(mode.range(), sampleRate, stemInsert, tailSeconds, 1f, isCancelled, onProgress, onChunk)

    /** Renders [range]; every sample is multiplied by [gain] (normalize) and clipped to ±1. */
    fun render(
        range: Range,
        sampleRate: Int,
        stemInsert: Int,
        tailSeconds: Double,
        gain: Float,
        isCancelled: () -> Boolean = { false },
        onProgress: (Float) -> Unit = {},
        onChunk: (FloatArray, Int) -> Unit,
    ): Boolean {
        val mode = if (range == Range.PATTERN) PlayMode.PATTERN else PlayMode.SONG
        // A full-song export must not follow the song's loop region.
        val source = if (range == Range.SONG) project.clearLoop() else project
        val start = if (range == Range.LOOP && project.song.hasLoop) project.song.loopStart else 0
        AudioEngine(realtime = false).use { engine ->
            engine.setRenderSampleRate(sampleRate)
            for (ref in project.samples) {
                loadSample(ref)?.let { engine.loadSample(ref.id, it.samples, it.channels, it.sampleRate) }
            }
            engine.load(source, mode)
            engine.setStemInsert(stemInsert)
            engine.setPosition(start)
            engine.play()
            val music = musicFrames(range, sampleRate)
            val total = totalFrames(range, sampleRate, tailSeconds)
            val buffer = FloatArray(CHUNK * 2)
            var done = 0L
            var stopped = false
            while (done < total) {
                if (isCancelled()) return false
                if (!stopped && done >= music) {
                    // End of the music: stop the transport (so it does not loop) and let tails ring out.
                    engine.stop()
                    stopped = true
                }
                val limit = if (stopped) total else music
                val n = minOf(CHUNK.toLong(), limit - done).toInt()
                engine.render(buffer, n)
                if (gain != 1f) for (i in 0 until n * 2) buffer[i] = (buffer[i] * gain).coerceIn(-1f, 1f)
                onChunk(buffer, n)
                done += n
                onProgress(done.toFloat() / total)
            }
        }
        return true
    }

    companion object {
        private const val CHUNK = 4096
    }
}
