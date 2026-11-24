package com.jeangbeat.core.data

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Streams PCM WAV to [out] chunk by chunk, so long songs never sit in memory.
 * [totalFrames] must be known up front (it goes in the header).
 */
class WavWriter(
    private val out: OutputStream,
    private val channels: Int,
    sampleRate: Int,
    private val bits: Int,
    totalFrames: Long,
) {
    private val bytesPerSample = bits / 8
    private var buffer = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)

    init {
        require(bits == 16 || bits == 24) { "bits must be 16 or 24" }
        val dataBytes = totalFrames * channels * bytesPerSample
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt((36 + dataBytes).toInt())
            put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1)
            putShort(channels.toShort()); putInt(sampleRate)
            putInt(sampleRate * channels * bytesPerSample)
            putShort((channels * bytesPerSample).toShort()); putShort(bits.toShort())
            put("data".toByteArray()); putInt(dataBytes.toInt())
        }
        out.write(header.array())
    }

    /** Writes [frames] interleaved frames from [samples] (-1..1, clipped). */
    fun write(samples: FloatArray, frames: Int) {
        val n = frames * channels
        val needed = n * bytesPerSample
        if (buffer.capacity() < needed) buffer = ByteBuffer.allocate(needed).order(ByteOrder.LITTLE_ENDIAN)
        buffer.clear()
        for (i in 0 until n) {
            val v = samples[i].coerceIn(-1f, 1f)
            if (bits == 16) {
                buffer.putShort((v * 32767f).roundToInt().toShort())
            } else {
                val s = (v * 8388607f).roundToInt()
                buffer.put((s and 0xFF).toByte()); buffer.put((s shr 8 and 0xFF).toByte()); buffer.put((s shr 16 and 0xFF).toByte())
            }
        }
        out.write(buffer.array(), 0, needed)
    }
}
