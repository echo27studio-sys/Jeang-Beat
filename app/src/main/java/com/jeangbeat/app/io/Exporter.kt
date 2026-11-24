package com.jeangbeat.app.io

import android.content.Context
import android.net.Uri
import com.jeangbeat.core.audio.OfflineRenderer
import com.jeangbeat.core.audio.PlayMode
import com.jeangbeat.core.data.WavWriter
import com.jeangbeat.core.model.Clip
import com.jeangbeat.core.model.Project
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ExportFormat(val mime: String, val extension: String) {
    WAV16("audio/wav", "wav"),
    WAV24("audio/wav", "wav"),
    M4A("audio/mp4", "m4a"),
    STEMS("application/zip", "zip"),
}

/** Renders a project offline and writes it to a document the user picked. */
class Exporter(private val context: Context, private val samples: SampleLibrary) {

    /** Renders and writes the file. On failure or cancel the partial document is deleted. */
    fun exportAudio(
        project: Project,
        mode: PlayMode,
        format: ExportFormat,
        uri: Uri,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
    ): Boolean {
        val ok = try {
            write(project, mode, format, uri, isCancelled, onProgress)
        } catch (e: Exception) {
            deletePartial(uri)
            throw e
        }
        if (!ok) deletePartial(uri)
        return ok
    }

    private fun deletePartial(uri: Uri) {
        runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
    }

    private fun write(
        project: Project,
        mode: PlayMode,
        format: ExportFormat,
        uri: Uri,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
    ): Boolean {
        val renderer = OfflineRenderer(project) { samples.load(it) }
        val rate = SAMPLE_RATE
        return when (format) {
            ExportFormat.WAV16, ExportFormat.WAV24 -> openOut(uri).use { out ->
                val buffered = out.buffered(1 shl 16)
                val bits = if (format == ExportFormat.WAV16) 16 else 24
                val writer = WavWriter(buffered, 2, rate, bits, renderer.totalFrames(mode, rate, TAIL))
                val ok = renderer.render(mode, rate, -1, TAIL, isCancelled, onProgress) { buf, n -> writer.write(buf, n) }
                buffered.flush()
                ok
            }
            ExportFormat.M4A -> {
                val pfd = context.contentResolver.openFileDescriptor(uri, "rw") ?: throw IOException("Cannot open output")
                pfd.use {
                    val aac = AudioCodec.AacWriter(it.fileDescriptor, rate)
                    var finished = false
                    try {
                        val ok = renderer.render(mode, rate, -1, TAIL, isCancelled, onProgress) { buf, n -> aac.write(buf, n) }
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
                    val total = renderer.totalFrames(mode, rate, TAIL)
                    // Full mix first, then one file per mixer insert.
                    val jobs = listOf(-1 to "00 Master mix") + inserts.map { i -> i to "%02d %s".format(i, sanitize(project.mixer.track(i)?.name ?: "Insert $i")) }
                    jobs.forEachIndexed { k, (insert, name) ->
                        zip.putNextEntry(ZipEntry("$name.wav"))
                        val w = WavWriter(zip, 2, rate, 24, total)
                        val ok = renderer.render(mode, rate, insert, TAIL, isCancelled, { p -> onProgress((k + p) / jobs.size) }) { buf, n -> w.write(buf, n) }
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
        const val SAMPLE_RATE = 48_000
        const val TAIL = 2.5
    }
}
