package com.marknote.app.ui.files

import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuOpen
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.marknote.app.R
import com.marknote.app.data.DocumentMeta
import com.marknote.app.data.DocumentRepository
import com.marknote.app.ui.common.NameInputDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileListScreen(
    repository: DocumentRepository,
    onOpenDocument: (String) -> Unit,
    onOpenInNewWindow: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onCollapse: (() -> Unit)? = null,
    refreshTick: Int = 0,
    showHiddenFiles: Boolean = false,
) {
    val viewModel: FileListViewModel = viewModel(key = "file-list") {
        FileListViewModel(repository)
    }

    // 从编辑器返回后刷新列表（摘要/时间可能已变化）
    LaunchedEffect(refreshTick) {
        if (refreshTick > 0) viewModel.refresh()
    }

    var pendingRemove by remember { mutableStateOf<DocumentMeta?>(null) }

    // 侧栏显示「最近文件」还是「文件夹」。状态在 ViewModel 里（不在 rememberSaveable）：
    // 窄屏与宽屏是两个不同的组合位置，平板旋转会让宽度类在 840dp 上来回翻，
    // 存本地 saveable 状态时用户选的那一档会被静默重置
    val mode = viewModel.listMode

    // 系统目录选择器：给「打开文件」与文件夹浏览的「换文件夹」共用。
    // 放在这一层而不是 FolderBrowser 里 —— 两处入口都要用它，各自的 remember 会拿到两个
    // 互不相干的 launcher 实例，回调也就各写一份。
    val scope = rememberCoroutineScope()
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { picked ->
        if (picked != null) {
            scope.launch {
                // takePersistableUriPermission 要走系统 IPC，别放主线程
                withContext(Dispatchers.IO) { repository.persistFolderPermission(picked) }
                repository.setFolderTree(picked.toString())
                viewModel.onFolderChosen(picked.toString(), repository.folderRootName(picked).orEmpty())
            }
        }
    }

    // 系统文档选择器：在任意目录新建 .md 文件
    val createLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        if (uri != null) viewModel.onDocumentPicked(uri, onOpenDocument)
    }

    // 新建文件的默认名要在 composable 作用域内取好，onClick 里不能调 stringResource
    val untitledName = stringResource(R.string.untitled_md)

    // 给「新建失败」这类一次性提示用（各屏自带 Scaffold、没有 Snackbar 宿主）
    val context = LocalContext.current

    // 文件夹档里「长按新建」弹出的输入框
    var showNewFolderDialog by remember { mutableStateOf(false) }

    /**
     * 在**当前文件夹**里新建一个条目（[isFolder] 决定是文件夹还是 .md 文档）。
     *
     * 不弹系统选择器：用户点「新建」时想的是「就在这儿建」，再走一遍选择器等于把已经选过的
     * 位置又问一遍（用户 2026-09-20 提的）。名字不满意可以长按条目重命名；重名时 provider
     * 自己加后缀（本机是 `未命名 (1).md`），不会覆盖已有文件。
     *
     * 失败（只读授权、名字非法、provider 不支持）给一句提示，不静默。
     */
    fun createInCurrentFolder(displayName: String, isFolder: Boolean, openAfterwards: Boolean) {
        val tree = viewModel.folderTree ?: return
        val folder = viewModel.currentFolderUri ?: return
        scope.launch {
            val created = withContext(Dispatchers.IO) {
                repository.createDocument(
                    treeUri = Uri.parse(tree),
                    parentUri = Uri.parse(folder),
                    displayName = displayName,
                    mimeType = if (isFolder) {
                        DocumentsContract.Document.MIME_TYPE_DIR
                    } else {
                        "text/markdown"
                    },
                )
            }
            if (created == null) {
                Toast.makeText(context, R.string.create_failed, Toast.LENGTH_SHORT).show()
            } else {
                viewModel.bumpFolderRevision()
                if (openAfterwards) onOpenDocument(created.toString())
            }
        }
    }

    Scaffold(
        topBar = {
            // 普通顶栏（不是 LargeTopAppBar）：标题与图标同一行，省下一整行给列表；
            // 侧栏本来就不高，大标题那点「高级感」不值一行位置
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.settings),
                        )
                    }
                    if (onCollapse != null) {
                        IconButton(onClick = onCollapse) {
                            Icon(
                                Icons.AutoMirrored.Outlined.MenuOpen,
                                contentDescription = stringResource(R.string.collapse_sidebar),
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            // 只有一个按钮。原先还有一颗「打开文件」的文件夹图标，它的全部作用就是切到「文件夹」档 ——
            // 而那一档就在上面的分段按钮里，属于重复入口（用户 2026-09-20 提的），去掉后「新建」落回原位。
            //
            // 自己拼一个而不是用 SmallFloatingActionButton：Material3 的 FAB 不支持长按，
            // 而文件夹档要用长按唤出「新建文件夹」。外观照 FAB 的默认值来（40dp、medium 圆角、
            // secondaryContainer 底色），免得两种按钮长得不一样。
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .shadow(elevation = 6.dp, shape = MaterialTheme.shapes.medium)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .combinedClickable(
                        onClick = {
                            if (viewModel.listMode == LIST_MODE_FOLDER && viewModel.folderTree != null) {
                                // 文件夹档：直接在**当前目录**建，不再走系统选择器 ——
                                // 用户要的就是「在这儿新建一个」。
                                createInCurrentFolder(untitledName, isFolder = false, openAfterwards = true)
                            } else {
                                // 最近文件档没有「当前目录」可言，只能让系统选择器问位置
                                createLauncher.launch(untitledName)
                            }
                        },
                        onLongClick = {
                            // 长按只在文件夹档有意义（最近档没有当前目录）
                            if (viewModel.listMode == LIST_MODE_FOLDER && viewModel.folderTree != null) {
                                showNewFolderDialog = true
                            }
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.new_file),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 「最近文件 / 文件夹」切换。窄屏时这里是整页入口，宽屏时它就是侧栏顶部的那一档 ——
            // 两种布局共用同一份代码，不给宽屏单独做一套
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                SegmentedButton(
                    selected = mode == LIST_MODE_RECENT,
                    onClick = { viewModel.selectListMode(LIST_MODE_RECENT) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) {
                    Text(stringResource(R.string.browse_recent))
                }
                SegmentedButton(
                    selected = mode == LIST_MODE_FOLDER,
                    onClick = { viewModel.selectListMode(LIST_MODE_FOLDER) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) {
                    Text(stringResource(R.string.browse_folder))
                }
            }

            when {
                // 文件夹模式：授权一个文件夹，之后就在应用内浏览（不再每次走系统选择器）
                mode == LIST_MODE_FOLDER -> FolderBrowser(
                    viewModel = viewModel,
                    repository = repository,
                    onOpenDocument = { uri, treeUriString ->
                        viewModel.onFolderDocumentPicked(
                            uri = Uri.parse(uri),
                            treeUriString = treeUriString,
                            onOpen = onOpenDocument,
                        )
                    },
                    refreshTick = refreshTick,
                    showHiddenFiles = showHiddenFiles,
                    onOpenInNewWindow = onOpenInNewWindow,
                    onPickFolder = { folderPicker.launch(null) },
                )

                viewModel.documents.isEmpty() && !viewModel.isLoading -> EmptyState()

                else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                    val wide = maxWidth >= 600.dp
                    // 底部留出 FAB 区域，避免卡片被遮挡
                    val listPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 8.dp, bottom = 176.dp,
                    )
                    // 卡片内容只写一份：宽屏网格与窄屏列表的 items 是同一套渲染
                    val card: @Composable (DocumentMeta) -> Unit = { doc ->
                        DocumentCard(
                            doc = doc,
                            timeText = viewModel.formatTime(doc.openedAt),
                            onClick = { onOpenDocument(doc.uri) },
                            onOpenInNewWindow = { onOpenInNewWindow(doc.uri) },
                            onRemove = { pendingRemove = doc },
                        )
                    }
                    if (wide) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 340.dp),
                            contentPadding = listPadding,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(viewModel.documents, key = { it.uri }) { card(it) }
                        }
                    } else {
                        LazyColumn(
                            contentPadding = listPadding,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(viewModel.documents, key = { it.uri }) { card(it) }
                        }
                    }
                }
            }
        }
    }

    pendingRemove?.let { doc ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text(stringResource(R.string.remove_from_list)) },
            text = {
                Text(stringResource(R.string.remove_dialog_message, doc.name))
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemove = null
                    viewModel.remove(doc.uri)
                }) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // 长按「新建」→ 新建文件夹：要名字，所以弹输入框。
    // 新建**文件**不弹（用默认名建完直接打开，要改名长按条目即可），少一步。
    if (showNewFolderDialog) {
        NameInputDialog(
            title = stringResource(R.string.new_folder),
            initial = "",
            confirmLabel = stringResource(R.string.create),
            onDismiss = { showNewFolderDialog = false },
            onConfirm = { name ->
                showNewFolderDialog = false
                createInCurrentFolder(name, isFolder = true, openAfterwards = false)
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocumentCard(
    doc: DocumentMeta,
    timeText: String,
    onClick: () -> Unit,
    onOpenInNewWindow: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            // 长按出菜单。Card 那个带 onClick 的重载没有长按，所以改用 combinedClickable，
            // 并先把圆角 clip 上 —— 否则水波纹会画成圆角之外的方角。
            .clip(CardDefaults.shape)
            .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true }),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 4.dp, bottom = 12.dp)) {
            Text(
                text = doc.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 12.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = when {
                    !doc.accessible -> stringResource(R.string.list_item_unavailable)
                    doc.snippet.isNotEmpty() -> doc.snippet
                    else -> stringResource(R.string.empty_document)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (doc.accessible) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 12.dp),
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.remove_from_list),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            // 长按菜单。这里只放一项：卡片尾部的 × 仍然是「移出列表」，两者不重复。
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.open_in_new_window)) },
                    onClick = {
                        menuOpen = false
                        onOpenInNewWindow()
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.empty_list_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.empty_list_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

