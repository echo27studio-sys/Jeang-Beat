package com.jeangbeat.core.audio

import com.jeangbeat.core.model.Project
import com.jeangbeat.core.model.SampleRef
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
    /** Frames of music (rounded down so playback never wraps back to the start). */
    fun musicFrames(mode: PlayMode, sampleRate: Int): Long {
        val ticks = if (mode == PlayMode.SONG) project.song.lengthTicks else project.currentPattern.lengthTicks
        return floor(ticks * AudioEngine.framesPerTick(sampleRate, project.tempo)).toLong()
    }

    /** Total frames: the music plus a reverb/delay tail. */
    fun totalFrames(mode: PlayMode, sampleRate: Int, tailSeconds: Double): Long =
        musicFrames(mode, sampleRate) + (tailSeconds * sampleRate).toLong()

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
    ): Boolean {
        AudioEngine(realtime = false).use { engine ->
            engine.setRenderSampleRate(sampleRate)
            for (ref in project.samples) {
                loadSample(ref)?.let { engine.loadSample(ref.id, it.samples, it.channels, it.sampleRate) }
            }
            engine.load(project, mode)
            engine.setStemInsert(stemInsert)
            engine.setPosition(0)
            engine.play()
            val music = musicFrames(mode, sampleRate)
            val total = totalFrames(mode, sampleRate, tailSeconds)
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
