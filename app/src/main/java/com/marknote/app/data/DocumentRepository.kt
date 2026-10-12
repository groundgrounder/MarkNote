package com.marknote.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.system.Os
import com.marknote.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

data class DocumentMeta(
    val uri: String,
    val name: String,
    val snippet: String,
    val accessible: Boolean,
    val openedAt: Long,
)

class DocumentRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("recent_docs", Context.MODE_PRIVATE)
    private val recentStore = RecentDocumentsStore(prefs)

    private val timeFormatter = DocumentTimeFormatter(context)

    suspend fun readDocument(uri: Uri): DecodedText? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBounded(MAX_DOCUMENT_BYTES)?.let(TextEncoding::decode)
            }
        }.getOrNull()
    }

    private fun InputStream.readBounded(limit: Int): ByteArray? {
        val out = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > limit) return null
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    fun probe(uri: Uri, limit: Int = 512): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(limit)
            val n = input.read(buf)
            if (n > 0) TextEncoding.decodeTruncated(buf.copyOf(n)).text else ""
        }
    }.getOrNull()

    suspend fun saveDocument(uri: Uri, content: String, encoding: DocumentEncoding): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = TextEncoding.encode(content, encoding)
                if (!writeOnce(uri, "wt", bytes) && !writeOnce(uri, "w", bytes)) {
                    return@runCatching false
                }

                lengthMatches(uri, bytes)
            }.getOrDefault(false)
        }

    private fun writeOnce(uri: Uri, mode: String, bytes: ByteArray): Boolean = runCatching {
        context.contentResolver.openOutputStream(uri, mode)?.use { it.write(bytes) } != null
    }.getOrDefault(false)

    private fun lengthMatches(uri: Uri, bytes: ByteArray): Boolean {
        val size = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        }.getOrNull()

        return size == null || size < 0 || size == bytes.size.toLong()
    }

    fun displayName(uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    cursor.getString(idx)?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
        recentStore.nameFor(uri.toString())
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return uri.lastPathSegment?.substringAfterLast('/') ?: context.getString(R.string.untitled_md)
    }

    suspend fun fileIdentity(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val stat = Os.fstat(pfd.fileDescriptor)
                "${stat.st_dev}:${stat.st_ino}"
            }
        }.getOrNull()
    }

    fun hasPersistedPermission(uri: Uri): Boolean = context.contentResolver
        .persistedUriPermissions
        .any { permission ->
            permission.isReadPermission &&
                (permission.uri == uri || uri.toString().startsWith(permission.uri.toString() + "/"))
        }

    fun persistPermission(uri: Uri): Boolean {
        if (hasPersistedPermission(uri)) return true
        val resolver = context.contentResolver

        runCatching {
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure {
            runCatching {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        return hasPersistedPermission(uri)
    }

    suspend fun canWrite(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.checkUriPermission(
                uri,
                Process.myPid(),
                Process.myUid(),
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            ) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(true)
    }

    @Synchronized
    fun addToRecents(uri: Uri, treeUriString: String? = null) {
        val key = uri.toString()
        val oldName = recentStore.nameFor(key)
        recentStore.add(
            uri = key,
            name = oldName?.ifBlank { null } ?: displayName(uri),
            tree = treeUriString,
            time = System.currentTimeMillis(),
        )
    }

    @Synchronized
    fun replaceRecent(oldUriString: String, newUri: Uri) {
        if (oldUriString == newUri.toString()) {
            addToRecents(newUri)
            return
        }
        val old = recentStore.entries().firstOrNull { it.uri == oldUriString }
        recentStore.replace(
            oldUri = oldUriString,
            entry = RecentEntry(
                uri = newUri.toString(),
                name = displayName(newUri).ifBlank { old?.name.orEmpty() },
                time = old?.time ?: System.currentTimeMillis(),
                tree = old?.tree,
            ),
        )
    }

    @Synchronized
    fun removeFromRecents(uriString: String) {
        recentStore.remove(uriString)
    }

    suspend fun recentDocuments(): List<DocumentMeta> = withContext(Dispatchers.IO) {
        recentStore.entries().sortedByDescending { it.time }.map { entry ->
            val uri = Uri.parse(entry.uri)
            val head = probe(uri)
            DocumentMeta(
                uri = entry.uri,
                name = entry.name.ifBlank { displayName(uri) },
                snippet = head.orEmpty().lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .take(2)
                    .joinToString("  ")
                    .take(80),
                accessible = head != null,
                openedAt = entry.time,
            )
        }
    }

    fun folderTree(): String? {
        val stored = prefs.getString(KEY_FOLDER_TREE, null)?.ifBlank { null } ?: return null
        return if (hasPersistedPermission(Uri.parse(stored))) stored else null
    }

    fun setFolderTree(treeUriString: String?) {
        prefs.edit().putString(KEY_FOLDER_TREE, treeUriString).apply()
    }

    fun folderTreeStored(): String? = prefs.getString(KEY_FOLDER_TREE, null)?.ifBlank { null }

    fun persistFolderPermission(treeUri: Uri): Boolean = persistPermission(treeUri)

    fun folderRootName(treeUri: Uri): String? =
        runCatching { folderDisplayName(DocumentsContract.getTreeDocumentId(treeUri)) }.getOrNull()

    suspend fun listFolder(
        treeUri: Uri,
        folderUri: Uri,
        showHiddenFiles: Boolean,
    ): List<FolderEntry> = withContext(Dispatchers.IO) {
        val documentId = runCatching { DocumentsContract.getDocumentId(folderUri) }
            .getOrElse { DocumentsContract.getTreeDocumentId(folderUri) }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val entries = mutableListOf<FolderEntry>()
        runCatching {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2).orEmpty()
                    if (!isBrowsableEntry(name, mime, showHiddenFiles)) continue
                    entries += FolderEntry(
                        name = name,
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString(),
                        isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    )
                }
            }
        }.onFailure {
            android.util.Log.w(TAG, "列文件夹失败：$folderUri", it)
        }
        sortFolderEntries(entries)
    }

    suspend fun createDocument(
        treeUri: Uri,
        parentUri: Uri,
        displayName: String,
        mimeType: String,
    ): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val parentId = runCatching { DocumentsContract.getDocumentId(parentUri) }
                .getOrElse { DocumentsContract.getTreeDocumentId(parentUri) }
            val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)

            val created = DocumentsContract.createDocument(
                context.contentResolver,
                parentDocUri,
                mimeType,
                displayName,
            ) ?: return@runCatching null
            DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getDocumentId(created),
            )
        }.getOrNull()
    }

    suspend fun renameDocument(uri: Uri, newName: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            DocumentsContract.renameDocument(context.contentResolver, uri, newName) != null
        }.getOrDefault(false)
    }

    suspend fun deleteDocument(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        }.getOrDefault(false)
    }

    fun setImageTree(docUriString: String, treeUriString: String) {
        recentStore.setTree(docUriString, treeUriString)
    }

    fun imageTreeFor(docUriString: String): String? =
        recentStore.treeFor(docUriString)

    fun formatTime(epochMillis: Long): String = timeFormatter.format(epochMillis)

    private companion object {
        const val TAG = "MarkNote-documents"

        const val MAX_DOCUMENT_BYTES = 8 * 1024 * 1024

        const val KEY_FOLDER_TREE = "folder_tree"
    }
}

internal fun salvageEntryJson(raw: String): List<String> =
    Regex("\\{[^{}]*\\}").findAll(raw).map { it.value }.toList()
