package com.jeangbeat.core.data.browser

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Browser filter groups (FL Studio-like). */
@Serializable
enum class SampleCategory { DRUMS, LOOPS, ONE_SHOTS, INSTRUMENTS, VOCALS, FX }

/** A folder the user linked with the system folder picker (SAF tree URI). */
@Serializable
data class LinkedFolder(
    /** The tree URI, as a string. Also the folder's id. */
    val uri: String,
    val name: String,
)

/** One audio file found in a linked folder. */
@Serializable
data class IndexedSample(
    /** [LinkedFolder.uri] of the folder it was found in. */
    val folder: String,
    /** SAF document id (stable while the file stays where it is). */
    val documentId: String,
    /** Path inside the linked folder, e.g. "Drums/Kicks/Kick 808.wav". */
    val path: String,
    val size: Long,
    val lastModified: Long,
    val category: SampleCategory,
    val bpm: Int? = null,
    val key: String? = null,
) {
    /** File name without the extension. */
    val name: String get() = path.substringAfterLast('/').substringBeforeLast('.')

    /** Sub-folder inside the linked folder ("" at the top). */
    val dir: String get() = path.substringBeforeLast('/', "")

    /** Unique key for favourites and recents. */
    val key2: String get() = "$folder|$documentId"
}

/** Everything the Browser remembers. Stored as JSON in app storage. */
@Serializable
data class BrowserIndex(
    val folders: List<LinkedFolder> = emptyList(),
    val files: List<IndexedSample> = emptyList(),
    val favourites: Set<String> = emptySet(),
    /** Most recent first. */
    val recent: List<String> = emptyList(),
) {
    fun withFolder(folder: LinkedFolder): BrowserIndex =
        if (folders.any { it.uri == folder.uri }) this else copy(folders = folders + folder)

    fun withoutFolder(uri: String): BrowserIndex = copy(
        folders = folders.filterNot { it.uri == uri },
        files = files.filterNot { it.folder == uri },
        favourites = favourites.filterNot { it.startsWith("$uri|") }.toSet(),
        recent = recent.filterNot { it.startsWith("$uri|") },
    )

    /** Replaces the files of one folder with a new scan. */
    fun withScan(uri: String, scanned: List<IndexedSample>): BrowserIndex =
        copy(files = files.filterNot { it.folder == uri } + scanned)

    fun toggleFavourite(key: String): BrowserIndex =
        copy(favourites = if (key in favourites) favourites - key else favourites + key)

    fun markRecent(key: String): BrowserIndex = copy(recent = (listOf(key) + recent.filterNot { it == key }).take(MAX_RECENT))

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        const val MAX_RECENT = 30
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        fun fromJson(text: String): BrowserIndex = runCatching { json.decodeFromString(serializer(), text) }.getOrDefault(BrowserIndex())
    }
}

/** Search and filters for the Browser list. */
data class BrowserQuery(
    val text: String = "",
    val category: SampleCategory? = null,
    val favouritesOnly: Boolean = false,
    val bpmMin: Int? = null,
    val bpmMax: Int? = null,
    val key: String? = null,
) {
    val isEmpty: Boolean get() = text.isBlank() && category == null && !favouritesOnly && bpmMin == null && bpmMax == null && key == null

    fun matches(s: IndexedSample, favourites: Set<String>): Boolean {
        if (favouritesOnly && s.key2 !in favourites) return false
        if (category != null && s.category != category) return false
        if (bpmMin != null && (s.bpm == null || s.bpm < bpmMin)) return false
        if (bpmMax != null && (s.bpm == null || s.bpm > bpmMax)) return false
        if (key != null && !s.key.equals(key, ignoreCase = true)) return false
        if (text.isNotBlank()) {
            val words = text.lowercase().split(' ').filter { it.isNotBlank() }
            val hay = s.path.lowercase()
            if (!words.all { it in hay }) return false
        }
        return true
    }

    fun apply(index: BrowserIndex): List<IndexedSample> =
        index.files.filter { matches(it, index.favourites) }.sortedBy { it.path.lowercase() }
}
