package com.jeangbeat.app.io

import android.content.Context
import android.net.Uri
import com.jeangbeat.core.audio.Mp3Writer
import com.jeangbeat.core.audio.OfflineRenderer
import com.jeangbeat.core.data.WavWriter
import com.jeangbeat.core.model.Clip
import com.jeangbeat.core.model.Project
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ExportFormat(val mime: String, val extension: String) {
    MP3("audio/mpeg", "mp3"),
    WAV16("audio/wav", "wav"),
    WAV24("audio/wav", "wav"),
    WAV32F("audio/wav", "wav"),
    M4A("audio/mp4", "m4a"),
    STEMS("application/zip", "zip"),
}

/** Everything the Export dialog asks for. */
data class ExportOptions(
    val format: ExportFormat = ExportFormat.MP3,
    val range: OfflineRenderer.Range = OfflineRenderer.Range.SONG,
    val sampleRate: Int = 44_100,
    val mp3Kbps: Int = 320,
    val normalize: Boolean = false,
    val tailSeconds: Double = 2.5,
) : java.io.Serializable

/** Renders a project offline and writes it to a document (picker or MediaStore). */
class Exporter(private val context: Context, private val samples: SampleLibrary) {

    /** Renders and writes the file. On failure or cancel the partial document is deleted. */
    fun exportAudio(
        project: Project,
        options: ExportOptions,
        uri: Uri,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
    ): Boolean {
        val ok = try {
            write(project, options, uri, isCancelled, onProgress)
        } catch (e: Exception) {
            deletePartial(uri)
            throw e
        }
        if (!ok) deletePartial(uri)
        return ok
    }

    private fun deletePartial(uri: Uri) {
        // Works for picker documents and for MediaStore entries.
        val deleted = runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
        if (!deleted) runCatching { context.contentResolver.delete(uri, null, null) }
    }

    private fun write(
        project: Project,
        o: ExportOptions,
        uri: Uri,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
    ): Boolean {
        val renderer = OfflineRenderer(project) { samples.load(it) }
        val rate = o.sampleRate

        // Normalize: a silent first pass finds the peak, the second pass applies the gain.
        var gain = 1f
        var progress = onProgress
        if (o.normalize && o.format != ExportFormat.STEMS) {
            val peak = renderer.peak(o.range, rate, o.tailSeconds, isCancelled) { onProgress(it / 2) }
            if (isCancelled()) return false
            if (peak > 1e-4f) gain = (TARGET_PEAK / peak).coerceAtMost(MAX_NORMALIZE_GAIN)
            progress = { onProgress(0.5f + it / 2) }
        }
        val total = renderer.totalFrames(o.range, rate, o.tailSeconds)

        return when (o.format) {
            ExportFormat.WAV16, ExportFormat.WAV24, ExportFormat.WAV32F -> openOut(uri).use { out ->
                val buffered = out.buffered(1 shl 16)
                val bits = when (o.format) {
                    ExportFormat.WAV16 -> 16
                    ExportFormat.WAV24 -> 24
                    else -> 32
                }
                val writer = WavWriter(buffered, 2, rate, bits, total)
                val ok = renderer.render(o.range, rate, -1, o.tailSeconds, gain, isCancelled, progress) { buf, n -> writer.write(buf, n) }
                buffered.flush()
                ok
            }
            ExportFormat.MP3 -> openOut(uri).use { out ->
                val buffered = out.buffered(1 shl 16)
                Mp3Writer(buffered, rate, o.mp3Kbps).use { mp3 ->
                    val ok = renderer.render(o.range, rate, -1, o.tailSeconds, gain, isCancelled, progress) { buf, n -> mp3.write(buf, n) }
                    mp3.finish()
                    buffered.flush()
                    ok
                }
            }
            ExportFormat.M4A -> {
                val pfd = context.contentResolver.openFileDescriptor(uri, "rw") ?: throw IOException("Cannot open output")
                pfd.use {
                    val aac = AudioCodec.AacWriter(it.fileDescriptor, rate)
                    var finished = false
                    try {
                        val ok = renderer.render(o.range, rate, -1, o.tailSeconds, gain, isCancelled, progress) { buf, n -> aac.write(buf, n) }
                        aac.finish()
                        finished = true
                        ok
                    } finally {
                        if (!finished) aac.release()
                    }
                }
            }
            ExportFormat.STEMS -> openOut(uri).use { out ->
                val inserts = usedInserts(project)
                ZipOutputStream(out.buffered(1 shl 16)).use { zip ->
                    // Full mix first, then one file per mixer insert.
                    val jobs = listOf(-1 to "00 Master mix") + inserts.map { i -> i to "%02d %s".format(i, sanitize(project.mixer.track(i)?.name ?: "Insert $i")) }
                    jobs.forEachIndexed { k, (insert, name) ->
                        zip.putNextEntry(ZipEntry("$name.wav"))
                        val w = WavWriter(zip, 2, rate, 24, total)
                        val ok = renderer.render(o.range, rate, insert, o.tailSeconds, 1f, isCancelled, { p -> onProgress((k + p) / jobs.size) }) { buf, n -> w.write(buf, n) }
                        zip.closeEntry()
                        if (!ok) return false
                    }
                }
                true
            }
        }
    }

    private fun openOut(uri: Uri) = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open output")

    /** Inserts that carry sound: used by channels or audio clips, or receiving sends. */
    private fun usedInserts(project: Project): List<Int> {
        val used = project.channels.map { it.mixerTrack }.toMutableSet()
        used += project.song.clips.filterIsInstance<Clip.AudioClip>().map { it.mixerTrack }
        project.mixer.tracks.forEach { t -> if (t.index in used) used += t.sends.map { it.target } }
        return used.filter { it > 0 }.sorted()
    }

    private fun sanitize(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40)

    companion object {
        /** Normalize to -0.3 dBFS. */
        const val TARGET_PEAK = 0.966f
        const val MAX_NORMALIZE_GAIN = 8f
    }
}
