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

    /**
     * 侧栏显示「最近文件」还是「文件夹」。
     *
     * ⚠️ 必须放 ViewModel，不能用 `rememberSaveable`：窄屏与宽屏是两套不同的组合位置，
     * 各自保存各自的 saveable 状态。平板一旋转，宽度类就在 840dp 上来回翻，用户选的这一档
     * 会被静默重置成默认值（2026-09-19 在 Pixel_Tablet 上实测到：竖着选了「文件夹」，
     * 转横屏回到「最近文件」）。ViewModel 挂在 Activity 上，两种布局共用同一个实例。
     */
    var listMode by mutableStateOf(LIST_MODE_RECENT)
        private set

    /** 文件夹浏览：已授权的树 Uri（null = 没授权或授权失效） */
    var folderTree by mutableStateOf<String?>(null)
        private set

    /** [folderTree] 是否已经问过系统了 —— 没问完之前界面什么都不显示，
     *  免得先闪一下「选择文件夹」再跳出文件列表 */
    var folderResolved by mutableStateOf(false)
        private set

    /** 根文件夹的显示名（面包屑用；取不出为空串） */
    var folderRootName by mutableStateOf("")
        private set

    /**
     * 下钻过的目录。**只留内部实现**：界面要的只是「当前在哪、能不能上一级」，
     * 把整条路径暴露出去只会让调用方自己算这些（还容易算错）。
     */
    private var crumbs by mutableStateOf<List<FolderCrumb>>(emptyList())

    /** 当前文件夹 Uri（还没授权时为 null） */
    val currentFolderUri: String? get() = crumbs.lastOrNull()?.uri ?: folderTree

    /** 当前文件夹的显示名（根目录时用根名） */
    val currentFolderName: String get() = crumbs.lastOrNull()?.name ?: folderRootName

    /** 还能不能往上一级（已经在根目录时为 false） */
    val canGoUp: Boolean get() = crumbs.isNotEmpty()

    init {
        refresh()
        // ⚠️ folderTree() 要问系统「这条 Uri 的持久化授权还在不在」（跨进程 IPC），
        // 不能放主线程 —— 项目里所有跟 provider 打交道的调用都在 Dispatchers.IO
        viewModelScope.launch {
            val tree = withContext(Dispatchers.IO) { repository.folderTree() }
            folderTree = tree
            folderRootName = tree?.let { repository.folderRootName(Uri.parse(it)) }.orEmpty()
            folderResolved = true
        }
    }

    /** 属性是 private set，改名是为了避开 JVM 签名冲突（`var listMode` 自己就生成 setListMode） */
    fun selectListMode(mode: Int) {
        listMode = mode
    }

    /** 选好文件夹（或换了文件夹）后：记住授权、重置到根部 */
    fun onFolderChosen(treeUriString: String, rootName: String) {
        folderTree = treeUriString
        folderRootName = rootName
        crumbs = emptyList()
    }

    fun enterFolder(uri: String, name: String) {
        crumbs = crumbs + FolderCrumb(uri, name)
    }

    /** 上一级；已经在根部就什么都不做 */
    fun goUp() {
        if (crumbs.isNotEmpty()) crumbs = crumbs.dropLast(1)
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

    /**
     * 从文件夹浏览里打开一个文档。
     *
     * 与 [onDocumentPicked] 的唯一区别：**不申请持久化权限**。子文档 Uri 本来就不能单独持久化
     * （SAF 只允许持久化树 Uri），授权来自那个文件夹本身；照搬 picker 那条路会白失败一次，
     * 还会弹出「这个文件无法长期访问」——对用户来说是纯噪音。
     *
     * 顺带把文件夹记成该文档的图片根：相对路径图片最常见的形态就是「图和文档在同一个文件夹」。
     */
    fun onFolderDocumentPicked(uri: Uri, treeUriString: String?, onOpen: (String) -> Unit) {
        onOpen(uri.toString())
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.addToRecents(uri, treeUriString)
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

/** 文件夹浏览里下钻过的一级目录（只在 ViewModel 内部用） */
private data class FolderCrumb(val uri: String, val name: String)

/** 侧栏的两档：最近文件 / 文件夹（用 Int 存，状态放在 ViewModel 里，见 [FileListViewModel.listMode]） */
internal const val LIST_MODE_RECENT = 0
internal const val LIST_MODE_FOLDER = 1
