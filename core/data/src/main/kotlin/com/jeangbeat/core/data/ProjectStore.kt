package com.jeangbeat.core.data

import com.jeangbeat.core.model.DefaultProjects
import com.jeangbeat.core.model.Project
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/** Summary of a saved project for the project list. */
data class ProjectInfo(val fileName: String, val name: String, val modifiedMillis: Long)

class ProjectLoadException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Saves and loads projects as `.jbeat` JSON files in [directory].
 *
 * Writes go to a temporary file first and are then renamed, so a crash or a
 * full disk while saving never corrupts an existing project.
 */
class ProjectStore(private val directory: File) {

    init {
        directory.mkdirs()
    }

    fun list(): List<ProjectInfo> = directory
        .listFiles { f -> f.isFile && f.name.endsWith(EXTENSION) }
        .orEmpty()
        .mapNotNull { file ->
            runCatching { ProjectInfo(file.name, decode(file.readText()).name, file.lastModified()) }.getOrNull()
        }
        .sortedByDescending { it.modifiedMillis }

    /** Saves [project] and returns the file name used. */
    fun save(project: Project, fileName: String = fileNameFor(project.name)): String {
        val target = File(directory, fileName)
        val temp = File(directory, "$fileName.tmp")
        temp.writeText(encode(project))
        if (!temp.renameTo(target)) {
            // renameTo can fail if the target exists on some file systems.
            target.delete()
            if (!temp.renameTo(target)) throw IOException("Could not save $fileName")
        }
        return fileName
    }

    fun load(fileName: String): Project {
        val file = File(directory, fileName)
        if (!file.isFile) throw ProjectLoadException("Project not found: $fileName")
        return decode(file.readText())
    }

    fun delete(fileName: String): Boolean = File(directory, fileName).delete()

    fun exists(fileName: String): Boolean = File(directory, fileName).isFile

    /** A file name for [name] that does not clash with existing projects. */
    fun uniqueFileNameFor(name: String): String {
        val base = sanitize(name)
        var candidate = "$base$EXTENSION"
        var n = 2
        while (exists(candidate)) candidate = "$base ($n)$EXTENSION".also { n++ }
        return candidate
    }

    companion object {
        const val EXTENSION = ".jbeat"

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = false
            classDiscriminator = "type"
        }

        fun encode(project: Project): String = json.encodeToString(Project.serializer(), project)

        /** Parses a project and upgrades it from older file versions. */
        fun decode(text: String): Project = try {
            DefaultProjects.migrate(json.decodeFromString(Project.serializer(), text))
        } catch (e: SerializationException) {
            throw ProjectLoadException("Not a valid Jeang Beat project", e)
        } catch (e: IllegalArgumentException) {
            throw ProjectLoadException("Not a valid Jeang Beat project", e)
        }

        fun fileNameFor(name: String): String = sanitize(name) + EXTENSION

        /** Keeps letters (including Khmer), digits, spaces, dashes and underscores. */
        fun sanitize(name: String): String {
            val cleaned = name.trim()
                .map { c -> if (c.isLetterOrDigit() || c == ' ' || c == '-' || c == '_' || isKhmer(c)) c else '_' }
                .joinToString("")
                .trim()
                .take(60)
            return cleaned.ifEmpty { "Untitled" }
        }

        // Khmer vowel signs and diacritics are not "letters" in Java, so allow the whole block.
        private fun isKhmer(c: Char): Boolean = c in 'ក'..'៿' || c in '᧠'..'᧿'
    }
}
