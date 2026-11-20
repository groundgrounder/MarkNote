package com.marknote.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.system.Os
import android.util.Log
import com.marknote.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

sealed interface DocumentReadResult {
    data class Success(val document: DecodedText) : DocumentReadResult
    data class TooLarge(val limitMb: Int) : DocumentReadResult
    data object Unavailable : DocumentReadResult
}

sealed interface DocumentEvent {
    data class Renamed(val oldUri: String, val newUri: String, val newName: String) : DocumentEvent
    data class Deleted(val uri: String) : DocumentEvent
}

class DocumentRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("recent_docs", Context.MODE_PRIVATE)
    private val recentStore = RecentDocumentsStore(prefs)
    private val timeFormatter = DocumentTimeFormatter(context)

    private val _recentsVersion = MutableStateFlow(0L)
    val recentsVersion: StateFlow<Long> = _recentsVersion.asStateFlow()

    private val _folderVersion = MutableStateFlow(0L)
    val folderVersion: StateFlow<Long> = _folderVersion.asStateFlow()

    private val _documentEvents = MutableSharedFlow<DocumentEvent>(extraBufferCapacity = 8)
    val documentEvents: SharedFlow<DocumentEvent> = _documentEvents.asSharedFlow()

    private fun notifyRecentsChanged() {
        _recentsVersion.update { it + 1L }
    }

    private fun notifyFolderChanged() {
        _folderVersion.update { it + 1L }
    }

    suspend fun readDocumentResult(uri: Uri): DocumentReadResult = withContext(Dispatchers.IO) {
        val statSize = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        }.getOrNull()
        if (statSize != null && statSize > MAX_DOCUMENT_BYTES) {
            return@withContext DocumentReadResult.TooLarge(MAX_DOCUMENT_MB)
        }
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                when (val bounded = input.readBounded(MAX_DOCUMENT_BYTES)) {
                    is BoundedRead.Ok -> DocumentReadResult.Success(TextEncoding.decode(bounded.bytes))
                    BoundedRead.TooLarge -> DocumentReadResult.TooLarge(MAX_DOCUMENT_MB)
                }
            } ?: DocumentReadResult.Unavailable
        }.getOrDefault(DocumentReadResult.Unavailable)
    }

    private sealed interface BoundedRead {
        class Ok(val bytes: ByteArray) : BoundedRead
        data object TooLarge : BoundedRead
    }

    private fun InputStream.readBounded(limit: Int): BoundedRead {
        val out = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > limit) return BoundedRead.TooLarge
            out.write(buffer, 0, count)
        }
        return BoundedRead.Ok(out.toByteArray())
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
                val ok = lengthMatches(uri, bytes)
                if (ok) notifyRecentsChanged()
                ok
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
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.takeIf { it.isNotBlank() }?.let { return it }
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
        recentStore.add(
            uri = key,
            name = displayName(uri),
            tree = treeUriString,
            time = System.currentTimeMillis(),
        )
        notifyRecentsChanged()
    }

    @Synchronized
    fun replaceRecent(oldUriString: String, newUri: Uri, newNameOverride: String? = null) {
        if (oldUriString == newUri.toString() && newNameOverride == null) {
            addToRecents(newUri)
            return
        }
        val old = recentStore.entries().firstOrNull { it.uri == oldUriString }
        recentStore.replace(
            oldUri = oldUriString,
            entry = RecentEntry(
                uri = newUri.toString(),
                name = newNameOverride?.ifBlank { null }
                    ?: displayName(newUri).ifBlank { old?.name.orEmpty() },
                time = old?.time ?: System.currentTimeMillis(),
                tree = old?.tree,
            ),
        )
        notifyRecentsChanged()
    }

    @Synchronized
    fun removeFromRecents(uriString: String) {
        recentStore.remove(uriString)
        notifyRecentsChanged()
    }

    suspend fun recentDocuments(): List<DocumentMeta> = withContext(Dispatchers.IO) {
        val entries = recentStore.entries().sortedByDescending { it.time }
        if (entries.isEmpty()) return@withContext emptyList()
        val semaphore = Semaphore(RECENT_PROBE_CONCURRENCY)
        coroutineScope {
            entries.map { entry ->
                async {
                    semaphore.withPermit {
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
            }.awaitAll()
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
            Log.w(TAG, "Failed to list folder: $folderUri", it)
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
            val uri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getDocumentId(created),
            )
            notifyFolderChanged()
            uri
        }.getOrNull()
    }

    suspend fun renameDocument(treeUri: Uri?, uri: Uri, newName: String): Uri? =
        withContext(Dispatchers.IO) {
            runCatching {
                val renamed = DocumentsContract.renameDocument(context.contentResolver, uri, newName)
                    ?: return@runCatching null
                val resolved = if (treeUri != null) {
                    runCatching {
                        DocumentsContract.buildDocumentUriUsingTree(
                            treeUri,
                            DocumentsContract.getDocumentId(renamed),
                        )
                    }.getOrDefault(renamed)
                } else {
                    renamed
                }
                val oldKey = uri.toString()
                if (recentStore.nameFor(oldKey) != null) {
                    replaceRecent(oldKey, resolved, newNameOverride = newName)
                }
                _documentEvents.tryEmit(DocumentEvent.Renamed(oldKey, resolved.toString(), newName))
                notifyFolderChanged()
                resolved
            }.getOrNull()
        }

    suspend fun deleteDocument(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val deleted = DocumentsContract.deleteDocument(context.contentResolver, uri)
            if (deleted) {
                val key = uri.toString()
                removeFromRecents(key)
                _documentEvents.tryEmit(DocumentEvent.Deleted(key))
                notifyFolderChanged()
            }
            deleted
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
        const val MAX_DOCUMENT_MB = 8
        const val MAX_DOCUMENT_BYTES = MAX_DOCUMENT_MB * 1024 * 1024
        const val RECENT_PROBE_CONCURRENCY = 6
        const val KEY_FOLDER_TREE = "folder_tree"
    }
}

internal fun salvageEntryJson(raw: String): List<String> =
    Regex("\\{[^{}]*\\}").findAll(raw).map { it.value }.toList()
