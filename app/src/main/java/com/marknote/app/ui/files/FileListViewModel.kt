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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /**
     * 打开一个 Uri（来自系统选择器或外部 intent）：持久化权限 + 记入最近列表。
     *
     * 先切到编辑器，再在后台补登记：persistPermission 与 addToRecents 都要跟 provider 打交道
     * （后者内部会查 ContentProvider 取显示名），放在点击回调里同步做会拖住主线程。
     * 授权在本进程内已经生效（选择器刚授过），所以先打开不影响编辑器读写。
     */
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

    /** 从最近列表移除（不删除文件） */
    fun remove(uriString: String) {
        repository.removeFromRecents(uriString)
        refresh()
    }

    fun formatTime(epochMillis: Long): String = repository.formatTime(epochMillis)
}
