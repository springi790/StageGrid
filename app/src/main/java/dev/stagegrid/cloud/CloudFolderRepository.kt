package dev.stagegrid.cloud

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Read-only browser for a user-selected Storage Access Framework directory.
 *
 * Google Drive (and other cloud apps) can expose their files through a DocumentsProvider.
 * StageGrid never receives account credentials: the user grants access to one directory tree
 * through ACTION_OPEN_DOCUMENT_TREE and Android persists that URI permission.
 */
class CloudFolderRepository(private val context: Context) {
    data class FolderInfo(
        val documentId: String,
        val displayName: String,
    )

    data class Entry(
        val documentId: String,
        val name: String,
        val mimeType: String,
        val sizeBytes: Long?,
        val lastModifiedMs: Long?,
        val isDirectory: Boolean,
        val uri: Uri,
    )

    private val resolver get() = context.contentResolver
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun linkedTreeUri(): Uri? = preferences.getString(KEY_TREE_URI, null)
        ?.takeIf { it.isNotBlank() }
        ?.let(Uri::parse)

    fun link(treeUri: Uri) {
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            resolver.takePersistableUriPermission(treeUri, read or write)
        } catch (_: SecurityException) {
            // Some providers only grant read access. Read is all StageGrid needs for import.
            resolver.takePersistableUriPermission(treeUri, read)
        }
        preferences.edit().putString(KEY_TREE_URI, treeUri.toString()).apply()
    }

    fun unlink() {
        linkedTreeUri()?.let { uri ->
            val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
            val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { resolver.releasePersistableUriPermission(uri, read or write) }
                .recoverCatching { resolver.releasePersistableUriPermission(uri, read) }
        }
        preferences.edit().remove(KEY_TREE_URI).apply()
    }

    suspend fun rootInfo(treeUri: Uri): FolderInfo = withContext(Dispatchers.IO) {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        val name = resolver.query(
            rootUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        FolderInfo(rootId, name?.takeIf { it.isNotBlank() } ?: "Cloud folder")
    }

    suspend fun listChildren(treeUri: Uri, parentDocumentId: String): List<Entry> = withContext(Dispatchers.IO) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        val result = mutableListOf<Entry>()
        val cursor = resolver.query(childrenUri, projection, null, null, null)
            ?: return@withContext emptyList()

        cursor.use {
            val idColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            while (it.moveToNext()) {
                val id = it.getString(idColumn)
                val name = it.getString(nameColumn) ?: "Untitled"
                val mime = it.getString(mimeColumn).orEmpty()
                val isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR
                if (!isDirectory && !isImportable(name)) continue

                result += Entry(
                    documentId = id,
                    name = name,
                    mimeType = mime,
                    sizeBytes = if (it.isNull(sizeColumn)) null else it.getLong(sizeColumn),
                    lastModifiedMs = if (it.isNull(modifiedColumn)) null else it.getLong(modifiedColumn),
                    isDirectory = isDirectory,
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                )
            }
        }

        result.sortedWith(
            compareByDescending<Entry> { it.isDirectory }
                .thenBy { it.name.lowercase(Locale.getDefault()) },
        )
    }

    fun isArchive(entry: Entry): Boolean {
        val extension = entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension == "zip" || extension == "stagepack"
    }

    private fun isImportable(name: String): Boolean {
        if (name.equals("song.json", ignoreCase = true)) return true
        return name.substringAfterLast('.', "").lowercase(Locale.ROOT) in IMPORTABLE_EXTENSIONS
    }

    private companion object {
        const val PREFERENCES = "stagegrid_cloud_folder"
        const val KEY_TREE_URI = "tree_uri"
        val IMPORTABLE_EXTENSIONS = setOf("zip", "stagepack", "wav", "mp3")
    }
}
