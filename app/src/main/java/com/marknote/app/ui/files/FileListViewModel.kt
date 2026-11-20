package com.marknote.app.ui.files

import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.marknote.app.R
import com.marknote.app.data.DocumentMeta
import com.marknote.app.data.DocumentRepository
import com.marknote.app.data.FolderEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FileListViewModel(private val repository: DocumentRepository) : ViewModel() {

    private var refreshJob: Job? = null
    private var folderLoadJob: Job? = null
    private var showHiddenFiles = false

    var documents by mutableStateOf<List<DocumentMeta>>(emptyList())
        private set

    var isLoading by mutableStateOf(true)
        private set

    var listMode by mutableIntStateOf(LIST_MODE_RECENT)
        private set

    var folderTree by mutableStateOf<String?>(null)
        private set

    var folderEverChosen by mutableStateOf(false)
        private set

    var folderResolved by mutableStateOf(false)
        private set

    var folderRootName by mutableStateOf("")
        private set

    var folderEntries by mutableStateOf<List<FolderEntry>>(emptyList())
        private set

    var isFolderLoading by mutableStateOf(false)
        private set

    private var crumbs by mutableStateOf<List<FolderCrumb>>(emptyList())

    val currentFolderUri: String? get() = crumbs.lastOrNull()?.uri ?: folderTree
    val currentFolderName: String get() = crumbs.lastOrNull()?.name ?: folderRootName
    val canGoUp: Boolean get() = crumbs.isNotEmpty()

    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    init {
        refreshRecents()

        viewModelScope.launch {
            val (tree, stored) = withContext(Dispatchers.IO) {
                repository.folderTree() to repository.folderTreeStored()
            }
            folderTree = tree
            folderEverChosen = tree == null && stored != null
            folderRootName = tree?.let { repository.folderRootName(Uri.parse(it)) }.orEmpty()
            folderResolved = true
            reloadCurrentFolder()
        }

        viewModelScope.launch {
            repository.recentsVersion.drop(1).collect { refreshRecents() }
        }
        viewModelScope.launch {
            repository.folderVersion.drop(1).collect { reloadCurrentFolder() }
        }
    }

    fun setShowHiddenFiles(show: Boolean) {
        if (showHiddenFiles == show) return
        showHiddenFiles = show
        reloadCurrentFolder()
    }

    fun selectListMode(mode: Int) {
        listMode = mode
    }

    fun chooseFolder(picked: Uri) {
        viewModelScope.launch {
            val rootName = withContext(Dispatchers.IO) {
                if (!repository.persistFolderPermission(picked)) return@withContext null
                repository.setFolderTree(picked.toString())
                repository.folderRootName(picked).orEmpty()
            }
            if (rootName == null) {
                _messages.tryEmit(R.string.folder_permission_failed)
                return@launch
            }
            folderTree = picked.toString()
            folderEverChosen = false
            folderRootName = rootName
            crumbs = emptyList()
            folderEntries = emptyList()
            reloadCurrentFolder()
        }
    }

    fun enterFolder(uri: String, name: String) {
        crumbs = crumbs + FolderCrumb(uri, name)
        folderEntries = emptyList()
        reloadCurrentFolder()
    }

    fun goUp() {
        if (crumbs.isEmpty()) return
        crumbs = crumbs.dropLast(1)
        folderEntries = emptyList()
        reloadCurrentFolder()
    }

    fun createInCurrentFolder(
        displayName: String,
        isFolder: Boolean,
        onCreatedDocument: (String) -> Unit = {},
    ) {
        val tree = folderTree ?: return
        val folder = currentFolderUri ?: return
        viewModelScope.launch {
            val created = repository.createDocument(
                treeUri = Uri.parse(tree),
                parentUri = Uri.parse(folder),
                displayName = displayName,
                mimeType = if (isFolder) {
                    DocumentsContract.Document.MIME_TYPE_DIR
                } else {
                    "text/markdown"
                },
            )
            if (created == null) {
                _messages.tryEmit(R.string.create_failed)
            } else if (!isFolder) {
                withContext(Dispatchers.IO) {
                    repository.addToRecents(created, tree)
                }
                onCreatedDocument(created.toString())
            }
        }
    }

    fun renameEntry(
        entry: FolderEntry,
        newName: String,
        onRenamed: (oldUri: String, newUri: String, newName: String) -> Unit,
    ) {
        if (newName == entry.name) return
        val tree = folderTree?.let(Uri::parse)
        viewModelScope.launch {
            val renamedUri = repository.renameDocument(tree, Uri.parse(entry.uri), newName)
            if (renamedUri != null) {
                onRenamed(entry.uri, renamedUri.toString(), newName)
            } else {
                _messages.tryEmit(R.string.rename_failed)
            }
        }
    }

    fun deleteEntry(entry: FolderEntry, onDeleted: (uri: String) -> Unit) {
        viewModelScope.launch {
            val deleted = repository.deleteDocument(Uri.parse(entry.uri))
            if (deleted) {
                onDeleted(entry.uri)
            } else {
                _messages.tryEmit(R.string.delete_failed)
            }
        }
    }

    fun onDocumentPicked(uri: Uri, onOpen: (String) -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.persistPermission(uri)
                repository.addToRecents(uri)
            }
            onOpen(uri.toString())
        }
    }

    fun onFolderDocumentPicked(uri: Uri, treeUriString: String?, onOpen: (String) -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.addToRecents(uri, treeUriString)
            }
            onOpen(uri.toString())
        }
    }

    fun remove(uriString: String) {
        repository.removeFromRecents(uriString)
    }

    fun formatTime(epochMillis: Long): String = repository.formatTime(epochMillis)

    private fun refreshRecents() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            isLoading = true
            try {
                documents = repository.recentDocuments()
            } finally {
                if (isActive) isLoading = false
            }
        }
    }

    private fun reloadCurrentFolder() {
        val tree = folderTree
        val folder = currentFolderUri
        if (tree == null || folder == null) {
            folderEntries = emptyList()
            isFolderLoading = false
            return
        }
        folderLoadJob?.cancel()
        folderLoadJob = viewModelScope.launch {
            isFolderLoading = true
            try {
                folderEntries = repository.listFolder(
                    treeUri = Uri.parse(tree),
                    folderUri = Uri.parse(folder),
                    showHiddenFiles = showHiddenFiles,
                )
            } finally {
                if (isActive) isFolderLoading = false
            }
        }
    }
}

private data class FolderCrumb(val uri: String, val name: String)

internal const val LIST_MODE_RECENT = 0
internal const val LIST_MODE_FOLDER = 1
