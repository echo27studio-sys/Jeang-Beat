package com.jeangbeat.app.browser

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.jeangbeat.core.data.browser.DocEntry
import com.jeangbeat.core.data.browser.DocumentTree

/** A linked folder read through the Storage Access Framework. */
class SafDocumentTree(private val context: Context, private val treeUri: Uri) : DocumentTree {
    override val rootId: String = DocumentsContract.getTreeDocumentId(treeUri)

    override fun children(parentId: String): List<DocEntry> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val out = ArrayList<DocEntry>()
        context.contentResolver.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val dir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                out += DocEntry(id, name, dir, if (c.isNull(3)) 0 else c.getLong(3), if (c.isNull(4)) 0 else c.getLong(4))
            }
        }
        return out
    }

    companion object {
        fun documentUri(treeUri: Uri, documentId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

        /** The folder's name as the picker shows it. */
        fun displayName(context: Context, treeUri: Uri): String {
            val doc = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            return runCatching {
                context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: DocumentsContract.getTreeDocumentId(treeUri).substringAfterLast(':').substringAfterLast('/').ifBlank { "Folder" }
        }
    }
}
