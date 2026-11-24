package com.jeangbeat.app.io

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.jeangbeat.core.audio.SampleData
import com.jeangbeat.core.data.AudioData
import com.jeangbeat.core.data.WavFile
import com.jeangbeat.core.model.SampleRef
import java.io.File
import java.util.UUID
import kotlin.math.abs

/**
 * Imported audio lives in filesDir/samples as 24-bit WAV, shared by all
 * projects. Projects refer to samples by file name.
 */
class SampleLibrary(private val context: Context) {
    val dir: File = File(context.filesDir, "samples").apply { mkdirs() }

    /** Decodes [uri] and stores it. The returned ref has id 0; the project assigns the real id. */
    fun import(uri: Uri): SampleRef {
        val audio = AudioCodec.decode(context, uri)
        val name = displayName(uri)?.substringBeforeLast('.')?.take(40) ?: "Audio"
        val fileName = "${UUID.randomUUID()}.wav"
        WavFile.write(File(dir, fileName), audio, bits = 24)
        return SampleRef(0, name, fileName, audio.frames.toLong(), audio.sampleRate, audio.channels)
    }

    fun load(ref: SampleRef): SampleData? = runCatching {
        val a = WavFile.read(File(dir, ref.fileName))
        SampleData(a.samples, a.channels, a.sampleRate)
    }.getOrNull()

    /** Peak per bucket (0..1) for drawing waveforms. */
    fun peaks(data: SampleData, buckets: Int = 1024): FloatArray {
        val frames = data.samples.size / data.channels
        if (frames == 0) return FloatArray(0)
        val per = (frames / buckets).coerceAtLeast(1)
        return FloatArray(minOf(buckets, frames)) { b ->
            var m = 0f
            val start = b * per
            for (f in start until minOf(frames, start + per)) {
                for (c in 0 until data.channels) m = maxOf(m, abs(data.samples[f * data.channels + c]))
            }
            m
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    companion object {
        fun toAudioData(d: SampleData) = AudioData(d.samples, d.channels, d.sampleRate)
    }
}
