package com.jeangbeat.app.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.jeangbeat.core.data.browser.BrowserIndex
import com.jeangbeat.core.data.browser.FolderScanner
import com.jeangbeat.core.data.browser.IndexedSample
import com.jeangbeat.core.data.browser.LinkedFolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** What the Browser panel shows about the index. */
data class BrowserState(
    val index: BrowserIndex = BrowserIndex(),
    /** Files counted so far while a scan runs, or null when idle. */
    val scanning: Int? = null,
    /** Linked folders whose permission was lost (revoked, SD card removed). */
    val lost: Set<String> = emptySet(),
)

/**
 * The Browser's linked folders and their index. Folders are linked with the
 * system folder picker and keep their permission across restarts; the index is
 * a JSON file in app storage and is refreshed on start/resume and on demand.
 */
class BrowserRepository(private val context: Context) {
    private val file = File(context.filesDir, "browser/index.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(BrowserState())
    val state: StateFlow<BrowserState> = _state.asStateFlow()
    private var scanJob: Job? = null
    private var loaded = false

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val index = runCatching { BrowserIndex.fromJson(file.readText()) }.getOrDefault(BrowserIndex())
        _state.update { it.copy(index = index) }
    }

    private fun save() {
        val json = _state.value.index.toJson()
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json)
            tmp.renameTo(file)
        }
    }

    private fun updateIndex(transform: (BrowserIndex) -> BrowserIndex) {
        _state.update { it.copy(index = transform(it.index)) }
        scope.launch { synchronized(this@BrowserRepository) { save() } }
    }

    /** Links a folder picked with ACTION_OPEN_DOCUMENT_TREE and scans it. */
    fun addFolder(treeUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        scope.launch {
            ensureLoaded()
            val name = SafDocumentTree.displayName(context, treeUri)
            updateIndex { it.withFolder(LinkedFolder(treeUri.toString(), name)) }
            refresh()
        }
    }

    fun removeFolder(uri: String) {
        runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        updateIndex { it.withoutFolder(uri) }
    }

    fun toggleFavourite(key: String) = updateIndex { it.toggleFavourite(key) }

    fun markRecent(key: String) = updateIndex { it.markRecent(key) }

    /** Re-scans every linked folder (only new or changed files are parsed again). */
    fun refresh() {
        if (scanJob?.isActive == true) return
        scanJob = scope.launch {
            ensureLoaded()
            val granted = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
            val folders = _state.value.index.folders
            val lost = folders.map { it.uri }.filter { it !in granted }.toSet()
            _state.update { it.copy(lost = lost, scanning = 0) }
            var counted = 0
            for (folder in folders) {
                if (folder.uri in lost) continue
                val previous = _state.value.index.files
                val base = counted
                val scanned = runCatching {
                    FolderScanner.scan(folder.uri, SafDocumentTree(context, Uri.parse(folder.uri)), previous) { n ->
                        _state.update { it.copy(scanning = base + n) }
                    }
                }.getOrNull() ?: continue
                counted += scanned.size
                _state.update { it.copy(index = it.index.withScan(folder.uri, scanned)) }
            }
            _state.update { it.copy(scanning = null) }
            synchronized(this@BrowserRepository) { save() }
        }
    }

    /** Loads the saved index without scanning (fast; for app start). */
    fun load() = scope.launch { ensureLoaded() }

    fun uriOf(sample: IndexedSample): Uri = SafDocumentTree.documentUri(Uri.parse(sample.folder), sample.documentId)
}
