package com.jeangbeat.core.data

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Decoded audio: interleaved float samples in -1..1. */
class AudioData(val samples: FloatArray, val channels: Int, val sampleRate: Int) {
    val frames: Int get() = if (channels > 0) samples.size / channels else 0
}

/** Minimal WAV reader/writer (PCM 16/24/32-bit and 32-bit float). */
object WavFile {

    /** Writes [data] as PCM WAV with [bits] = 16 or 24. */
    fun write(out: OutputStream, data: AudioData, bits: Int = 16) {
        require(bits == 16 || bits == 24) { "bits must be 16 or 24" }
        val bytesPerSample = bits / 8
        val dataBytes = data.samples.size.toLong() * bytesPerSample
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt((36 + dataBytes).toInt())
            put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1) // PCM
            putShort(data.channels.toShort()); putInt(data.sampleRate)
            putInt(data.sampleRate * data.channels * bytesPerSample)
            putShort((data.channels * bytesPerSample).toShort()); putShort(bits.toShort())
            put("data".toByteArray()); putInt(dataBytes.toInt())
        }
        val buffered = BufferedOutputStream(out, 1 shl 16)
        buffered.write(header.array())
        val chunk = ByteBuffer.allocate(4096 * bytesPerSample).order(ByteOrder.LITTLE_ENDIAN)
        for (s in data.samples) {
            val v = s.coerceIn(-1f, 1f)
            if (bits == 16) {
                chunk.putShort((v * 32767f).roundToInt().toShort())
            } else {
                val i = (v * 8388607f).roundToInt()
                chunk.put((i and 0xFF).toByte()); chunk.put((i shr 8 and 0xFF).toByte()); chunk.put((i shr 16 and 0xFF).toByte())
            }
            if (chunk.remaining() < bytesPerSample) {
                buffered.write(chunk.array(), 0, chunk.position()); chunk.clear()
            }
        }
        buffered.write(chunk.array(), 0, chunk.position())
        buffered.flush()
    }

    fun write(file: File, data: AudioData, bits: Int = 16) = file.outputStream().use { write(it, data, bits) }

    fun read(file: File): AudioData = file.inputStream().buffered().use { read(it) }

    /** Reads a WAV file. Throws IOException for unsupported or broken files. */
    fun read(input: InputStream): AudioData {
        val din = DataInputStream(input)
        val riff = ByteArray(12)
        din.readFully(riff)
        if (String(riff, 0, 4) != "RIFF" || String(riff, 8, 4) != "WAVE") throw IOException("Not a WAV file")
        var format = 0
        var channels = 0
        var sampleRate = 0
        var bits = 0
        while (true) {
            val head = ByteArray(8)
            try { din.readFully(head) } catch (e: EOFException) { throw IOException("WAV has no data chunk") }
            val id = String(head, 0, 4)
            val size = ByteBuffer.wrap(head, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            if (id == "fmt ") {
                val fmt = ByteArray(size.toInt())
                din.readFully(fmt)
                val b = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                format = b.short.toInt() and 0xFFFF
                channels = b.short.toInt()
                sampleRate = b.int
                b.int; b.short
                bits = b.short.toInt()
                if (format == 0xFFFE && fmt.size >= 26) format = ByteBuffer.wrap(fmt, 24, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                if (size % 2 == 1L) din.skipBytes(1)
            } else if (id == "data") {
                if (channels <= 0 || sampleRate <= 0) throw IOException("WAV data before format")
                val bytes = ByteArray(size.toInt())
                din.readFully(bytes)
                return AudioData(decodeSamples(bytes, format, bits), channels, sampleRate)
            } else {
                var left = size + (size and 1)
                while (left > 0) { val n = din.skip(left); if (n <= 0) throw IOException("Truncated WAV"); left -= n }
            }
        }
    }

    private fun decodeSamples(bytes: ByteArray, format: Int, bits: Int): FloatArray {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return when {
            format == 1 && bits == 16 -> FloatArray(bytes.size / 2) { b.short / 32768f }
            format == 1 && bits == 24 -> FloatArray(bytes.size / 3) {
                val i = (bytes[it * 3].toInt() and 0xFF) or (bytes[it * 3 + 1].toInt() and 0xFF shl 8) or (bytes[it * 3 + 2].toInt() shl 16)
                i / 8388608f
            }
            format == 1 && bits == 32 -> FloatArray(bytes.size / 4) { b.int / 2147483648f }
            format == 3 && bits == 32 -> FloatArray(bytes.size / 4) { b.float }
            format == 1 && bits == 8 -> FloatArray(bytes.size) { ((bytes[it].toInt() and 0xFF) - 128) / 128f }
            else -> throw IOException("Unsupported WAV format $format / $bits-bit")
        }
    }
}
