package com.marknote.app.ui.files

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.marknote.app.data.DocumentMeta
import com.marknote.app.data.DocumentRepository
import kotlinx.coroutines.launch

class FileListViewModel(private val repository: DocumentRepository) : ViewModel() {

    var documents by mutableStateOf<List<DocumentMeta>>(emptyList())
        private set

    var isLoading by mutableStateOf(true)
        private set

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            isLoading = true
            documents = repository.recentDocuments()
            isLoading = false
        }
    }

    /** 打开一个 Uri（来自系统选择器或外部 intent）：持久化权限 + 记入最近列表 */
    fun onDocumentPicked(uri: Uri, onOpen: (String) -> Unit) {
        repository.persistPermission(uri)
        repository.addToRecents(uri)
        refresh()
        onOpen(uri.toString())
    }

    /** 从最近列表移除（不删除文件） */
    fun remove(uriString: String) {
        repository.removeFromRecents(uriString)
        refresh()
    }

    fun formatTime(epochMillis: Long): String = repository.formatTime(epochMillis)
}
