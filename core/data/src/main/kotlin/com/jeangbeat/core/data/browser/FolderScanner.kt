package com.jeangbeat.core.data.browser

/** A file or folder in a document tree (SAF on Android, a fake tree in tests). */
data class DocEntry(
    val id: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val lastModified: Long = 0,
)

/** Read access to a linked folder. */
interface DocumentTree {
    val rootId: String
    fun children(parentId: String): List<DocEntry>
}

/**
 * Walks a linked folder and lists its audio files. Files whose size and date did
 * not change since [previous] keep their earlier entry (only new or changed files
 * are parsed again).
 */
object FolderScanner {
    val AUDIO_EXTENSIONS = setOf("wav", "wave", "aif", "aiff", "mp3", "flac", "ogg", "m4a")
    const val MAX_FILE_BYTES = 200L * 1024 * 1024
    const val MAX_DEPTH = 12
    const val MAX_FILES = 20_000

    fun isAudio(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS

    fun scan(
        folderUri: String,
        tree: DocumentTree,
        previous: List<IndexedSample> = emptyList(),
        isCancelled: () -> Boolean = { false },
        onCount: (Int) -> Unit = {},
    ): List<IndexedSample> {
        val old = previous.filter { it.folder == folderUri }.associateBy { it.documentId }
        val out = ArrayList<IndexedSample>()
        // Iterative walk so deep folders cannot overflow the stack.
        val stack = ArrayDeque<Pair<String, String>>().apply { add(tree.rootId to "") }
        val depth = HashMap<String, Int>().apply { put(tree.rootId, 0) }
        while (stack.isNotEmpty() && out.size < MAX_FILES) {
            if (isCancelled()) return previous.filter { it.folder == folderUri }
            val (dirId, dirPath) = stack.removeLast()
            val children = runCatching { tree.children(dirId) }.getOrDefault(emptyList())
            for (e in children.sortedBy { it.name.lowercase() }) {
                if (e.name.startsWith('.')) continue
                val path = if (dirPath.isEmpty()) e.name else "$dirPath/${e.name}"
                if (e.isDirectory) {
                    val d = (depth[dirId] ?: 0) + 1
                    if (d <= MAX_DEPTH) {
                        depth[e.id] = d
                        stack.addLast(e.id to path)
                    }
                    continue
                }
                if (!isAudio(e.name) || e.size > MAX_FILE_BYTES) continue
                val before = old[e.id]
                out += if (before != null && before.size == e.size && before.lastModified == e.lastModified && before.path == path) {
                    before
                } else {
                    val parsed = SampleNameParser.parse(path)
                    IndexedSample(folderUri, e.id, path, e.size, e.lastModified, parsed.category, parsed.bpm, parsed.key)
                }
                if (out.size % 50 == 0) onCount(out.size)
            }
        }
        onCount(out.size)
        return out.sortedBy { it.path.lowercase() }
    }
}
