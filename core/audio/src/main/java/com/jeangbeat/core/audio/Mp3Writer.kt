package com.jeangbeat.core.audio

import java.io.Closeable
import java.io.IOException
import java.io.OutputStream

/**
 * Streams stereo float PCM to MP3 (CBR) using LAME (libmp3lame.so, LGPL 2+,
 * shipped as a separate shared library). Call [write] with chunks, then [finish].
 */
class Mp3Writer(private val out: OutputStream, sampleRate: Int, kbps: Int, channels: Int = 2) : Closeable {
    private var handle: Long = nativeOpen(sampleRate, channels, kbps)

    init {
        if (handle == 0L) throw IOException("MP3 encoder could not start ($sampleRate Hz, $kbps kbps)")
    }

    fun write(samples: FloatArray, frames: Int) {
        check(handle != 0L) { "MP3 writer is closed" }
        val bytes = nativeEncode(handle, samples, frames)
        if (bytes.isNotEmpty()) out.write(bytes)
    }

    /** Flushes the last frames and releases the encoder. */
    fun finish() {
        if (handle == 0L) return
        val bytes = nativeFlush(handle)
        if (bytes.isNotEmpty()) out.write(bytes)
        close()
    }

    override fun close() {
        if (handle != 0L) {
            nativeClose(handle)
            handle = 0L
        }
    }

    companion object {
        val BITRATES = listOf(128, 192, 320)

        init {
            System.loadLibrary("mp3lame")
            System.loadLibrary("jeangbeat_mp3")
        }

        @JvmStatic private external fun nativeOpen(sampleRate: Int, channels: Int, kbps: Int): Long
        @JvmStatic private external fun nativeEncode(handle: Long, pcm: FloatArray, frames: Int): ByteArray
        @JvmStatic private external fun nativeFlush(handle: Long): ByteArray
        @JvmStatic private external fun nativeClose(handle: Long)
    }
}
