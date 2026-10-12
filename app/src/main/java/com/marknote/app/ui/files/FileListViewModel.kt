package com.marknote.app.ui.files

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.marknote.app.data.DocumentMeta
import com.marknote.app.data.DocumentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FileListViewModel(private val repository: DocumentRepository) : ViewModel() {

    private var refreshJob: Job? = null

    var documents by mutableStateOf<List<DocumentMeta>>(emptyList())
        private set

    var isLoading by mutableStateOf(true)
        private set

    var listMode by mutableStateOf(LIST_MODE_RECENT)
        private set

    var folderTree by mutableStateOf<String?>(null)
        private set

    var folderResolved by mutableStateOf(false)
        private set

    var folderRevision by mutableStateOf(0)
        private set

    fun bumpFolderRevision() {
        folderRevision++
    }

    var folderRootName by mutableStateOf("")
        private set

    private var crumbs by mutableStateOf<List<FolderCrumb>>(emptyList())

    val currentFolderUri: String? get() = crumbs.lastOrNull()?.uri ?: folderTree

    val currentFolderName: String get() = crumbs.lastOrNull()?.name ?: folderRootName

    val canGoUp: Boolean get() = crumbs.isNotEmpty()

    init {
        refresh()

        viewModelScope.launch {
            val tree = withContext(Dispatchers.IO) { repository.folderTree() }
            folderTree = tree
            folderRootName = tree?.let { repository.folderRootName(Uri.parse(it)) }.orEmpty()
            folderResolved = true
        }
    }

    fun selectListMode(mode: Int) {
        listMode = mode
    }

    fun onFolderChosen(treeUriString: String, rootName: String) {
        folderTree = treeUriString
        folderRootName = rootName
        crumbs = emptyList()
    }

    fun enterFolder(uri: String, name: String) {
        crumbs = crumbs + FolderCrumb(uri, name)
    }

    fun goUp() {
        if (crumbs.isNotEmpty()) crumbs = crumbs.dropLast(1)
    }

    fun refresh() {
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

    fun onDocumentPicked(uri: Uri, onOpen: (String) -> Unit) {
        onOpen(uri.toString())
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.persistPermission(uri)
                repository.addToRecents(uri)
            }
            refresh()
        }
    }

    fun onFolderDocumentPicked(uri: Uri, treeUriString: String?, onOpen: (String) -> Unit) {
        onOpen(uri.toString())
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.addToRecents(uri, treeUriString)
            }
            refresh()
        }
    }

    fun remove(uriString: String) {
        repository.removeFromRecents(uriString)
        refresh()
    }

    fun formatTime(epochMillis: Long): String = repository.formatTime(epochMillis)
}

private data class FolderCrumb(val uri: String, val name: String)

internal const val LIST_MODE_RECENT = 0
internal const val LIST_MODE_FOLDER = 1
