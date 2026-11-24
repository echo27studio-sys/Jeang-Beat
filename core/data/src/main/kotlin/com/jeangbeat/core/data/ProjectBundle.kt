package com.jeangbeat.core.data

import com.jeangbeat.core.model.Project
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A shareable project file (.jbz): a zip with the project JSON and every
 * sample it uses, so it opens on another phone exactly as it was.
 *
 *   project.json
 *   samples/<fileName>.wav
 */
object ProjectBundle {
    const val EXTENSION = ".jbz"
    private const val PROJECT_ENTRY = "project.json"
    private const val SAMPLES_DIR = "samples/"
    private const val MAX_ENTRY_BYTES = 512L * 1024 * 1024

    fun export(project: Project, samplesDir: File, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(PROJECT_ENTRY))
            zip.write(ProjectStore.encode(project).toByteArray())
            zip.closeEntry()
            for (sample in project.samples) {
                val file = File(samplesDir, sample.fileName)
                if (!file.isFile) continue
                zip.putNextEntry(ZipEntry(SAMPLES_DIR + sample.fileName))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Reads a bundle, copies its samples into [samplesDir] (renaming any that
     * clash with existing files) and returns the project pointing at them.
     */
    fun import(input: InputStream, samplesDir: File): Project {
        samplesDir.mkdirs()
        var projectJson: String? = null
        val renamed = HashMap<String, String>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                when {
                    entry.name == PROJECT_ENTRY -> projectJson = zip.readLimited().toString(Charsets.UTF_8)
                    entry.name.startsWith(SAMPLES_DIR) -> {
                        val original = entry.name.removePrefix(SAMPLES_DIR)
                        // Never trust paths inside a zip: keep only a plain file name.
                        if (original.isEmpty() || original.contains('/') || original.contains('\\') || original.startsWith(".")) continue
                        var target = File(samplesDir, original)
                        if (target.exists()) target = File(samplesDir, "${UUID.randomUUID()}_$original")
                        target.outputStream().use { out -> zip.copyLimited(out) }
                        renamed[original] = target.name
                    }
                }
            }
        }
        val project = ProjectStore.decode(projectJson ?: throw ProjectLoadException("Not a Jeang Beat project bundle"))
        return project.copy(samples = project.samples.map { s -> s.copy(fileName = renamed[s.fileName] ?: s.fileName) })
    }

    private fun ZipInputStream.readLimited(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        copyLimited(out)
        return out.toByteArray()
    }

    private fun ZipInputStream.copyLimited(out: OutputStream) {
        val buf = ByteArray(1 shl 16)
        var total = 0L
        while (true) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_ENTRY_BYTES) throw IOException("Bundle entry too large")
            out.write(buf, 0, n)
        }
    }
}
