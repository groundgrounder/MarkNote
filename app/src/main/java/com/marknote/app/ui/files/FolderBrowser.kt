package com.marknote.app.ui.files

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.marknote.app.R
import com.marknote.app.data.DocumentRepository
import com.marknote.app.data.FolderEntry
import com.marknote.app.ui.common.NameInputDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 文件夹浏览：授权一个文件夹（SAF 树授权），在应用内列出其中的文本文件，
 * 点条目直接进编辑器，子目录可以点进去（顶部有「上一级」与「换文件夹」）。
 *
 * 它同时是应用的**文件选择器**：「打开文件」不再直接弹系统选择器，而是切到这一档 ——
 * 系统选择器只在「最近」视图按类型过滤，从目录里翻的时候二进制文件照样列出来
 * （平台行为，见 [com.marknote.app.data.PICKER_MIME_TYPES] 的说明），只有应用内这一层
 * 能保证「看不到不能编辑的文件」。
 *
 * [onPickFolder] 由调用方提供（选文件夹的 launcher 与「打开文件」按钮共用一个实例，
 * 放在这里会各自 remember 一份、互相看不见对方的回调）。
 *
 * ⚠️ 状态一律放 [FileListViewModel]，这里**不要**用 rememberSaveable：窄屏与宽屏是两套不同的
 * 组合位置，各自保存各自的 saveable 状态；平板一旋转宽度类就在 840dp 上来回翻，用户选的
 * 「文件夹」这一档、以及「进到了第几层」都会被静默重置（2026-09-19 在 Pixel_Tablet 实测到）。
 * ViewModel 挂在 Activity 上，两种布局共用同一个实例，不受组合位置影响。
 */
@Composable
fun FolderBrowser(
    viewModel: FileListViewModel,
    repository: DocumentRepository,
    onOpenDocument: (uri: String, treeUri: String?) -> Unit,
    onOpenInNewWindow: (uri: String) -> Unit,
    onPickFolder: () -> Unit,
    modifier: Modifier = Modifier,
    refreshTick: Int = 0,
    showHiddenFiles: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val treeUri = viewModel.folderTree
    // 授权失效（卸载重装、用户在系统里撤销）与「从没选过」要说不同的话
    val everChosen = remember(treeUri) { treeUri == null && repository.folderTreeStored() != null }

    var entries by remember { mutableStateOf<List<FolderEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    // 长按条目选出来的目标：重命名要拿它填初值，删除要先过确认框
    var renameTarget by remember { mutableStateOf<FolderEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<FolderEntry?>(null) }

    val currentUri = viewModel.currentFolderUri
    val currentName = viewModel.currentFolderName

    // 换文件夹 / 进出子目录 / 从编辑器返回（refreshTick）/ 设置里改了「显示隐藏文件」/
    // 在本目录里新建了东西（folderRevision）都要重列一次。
    // key 用**当前 Uri** 而不是路径深度：同深度换到另一个目录时也必须重列
    LaunchedEffect(currentUri, refreshTick, showHiddenFiles, viewModel.folderRevision) {
        val tree = treeUri ?: return@LaunchedEffect
        val folder = currentUri ?: return@LaunchedEffect
        loading = true
        entries = repository.listFolder(Uri.parse(tree), Uri.parse(folder), showHiddenFiles)
        loading = false
    }

    /**
     * 改完磁盘统一走这里：成功就重列（宽屏下侧栏一直挂着组合，不重列就显示旧内容），
     * 失败给一句提示 —— 最常见的原因是这条树授权只有读权限。
     */
    fun afterEdit(ok: Boolean, failMessage: Int) {
        if (ok) {
            viewModel.bumpFolderRevision()
        } else {
            Toast.makeText(context, failMessage, Toast.LENGTH_SHORT).show()
        }
    }

    fun renameEntry(entry: FolderEntry, newName: String) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                repository.renameDocument(Uri.parse(entry.uri), newName)
            }
            afterEdit(ok, R.string.rename_failed)
        }
    }

    fun deleteEntry(entry: FolderEntry) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                repository.deleteDocument(Uri.parse(entry.uri))
            }
            afterEdit(ok, R.string.delete_failed)
        }
    }

    Column(modifier.fillMaxSize()) {
        if (treeUri != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { viewModel.goUp() },
                    enabled = viewModel.canGoUp,
                ) {
                    Icon(
                        Icons.Outlined.ArrowUpward,
                        contentDescription = stringResource(R.string.up_one_level),
                    )
                }
                Text(
                    text = currentName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onPickFolder) {
                    Icon(
                        Icons.Outlined.FolderOpen,
                        contentDescription = stringResource(R.string.choose_folder),
                    )
                }
            }
        }

        when {
            // 授权还没问完：留空，避免先闪一下「选择文件夹」
            !viewModel.folderResolved -> Unit
            treeUri == null -> FolderHint(
                lostAccess = everChosen,
                onPick = onPickFolder,
            )
            entries.isEmpty() && !loading -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.folder_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                )
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(
                    start = 8.dp, end = 8.dp, top = 4.dp, bottom = 176.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(entries, key = { it.uri }) { entry ->
                    FolderRow(
                        entry = entry,
                        onClick = {
                            if (entry.isDirectory) {
                                viewModel.enterFolder(entry.uri, entry.name)
                            } else {
                                onOpenDocument(entry.uri, treeUri)
                            }
                        },
                        onRename = { renameTarget = entry },
                        onDelete = { deleteTarget = entry },
                        // 目录没有「在新窗口打开」这一项 —— 编辑器只开文档
                        onOpenInNewWindow = if (entry.isDirectory) {
                            null
                        } else {
                            { onOpenInNewWindow(entry.uri) }
                        },
                    )
                }
            }
        }
    }

    // 长按菜单的两项。重命名弹输入框（预填原名）；删除必须先确认 ——
    // SAF 在本机的 ExternalStorageProvider 上是**真删**，不进回收站，没有撤销的机会。
    renameTarget?.let { entry ->
        NameInputDialog(
            title = stringResource(R.string.rename),
            initial = entry.name,
            confirmLabel = stringResource(R.string.rename),
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                renameTarget = null
                // 没改就别白跑一趟 provider
                if (name != entry.name) renameEntry(entry, name)
            },
        )
    }

    deleteTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete)) },
            text = {
                Text(
                    stringResource(
                        // 删目录会连里面的东西一起删，这句必须说清楚
                        if (entry.isDirectory) {
                            R.string.delete_folder_message
                        } else {
                            R.string.delete_file_message
                        },
                        entry.name,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        deleteEntry(entry)
                    },
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/**
 * 文件夹里的一行：点进目录 / 点开文件，**长按唤出菜单**（重命名、删除、（文件才有）在新窗口打开）。
 *
 * 删除放在最后一项：它是唯一不可逆的操作，挨着另外两个容易误点。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRow(
    entry: FolderEntry,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onOpenInNewWindow: (() -> Unit)?,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = if (entry.isDirectory) {
                    Icons.Outlined.Folder
                } else {
                    Icons.AutoMirrored.Outlined.InsertDriveFile
                },
                contentDescription = null,
                tint = if (entry.isDirectory) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rename)) },
                onClick = {
                    menuOpen = false
                    onRename()
                },
            )
            if (onOpenInNewWindow != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.open_in_new_window)) },
                    onClick = {
                        menuOpen = false
                        onOpenInNewWindow()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete)) },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}

/** 还没选文件夹（或授权失效）时的引导 */
@Composable
private fun FolderHint(lostAccess: Boolean, onPick: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                text = stringResource(
                    if (lostAccess) R.string.folder_access_lost else R.string.folder_empty_hint,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onPick) {
                Text(stringResource(R.string.choose_folder))
            }
        }
    }
}
