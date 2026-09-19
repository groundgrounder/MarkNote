package com.marknote.app.ui.files

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.marknote.app.R
import com.marknote.app.data.DocumentRepository
import com.marknote.app.data.FolderEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 文件夹浏览：授权一个文件夹（SAF 树授权），在应用内列出其中的 Markdown / 纯文本文件，
 * 点条目直接进编辑器，子目录可以点进去（顶部有「上一级」与「换文件夹」）。
 *
 * 为什么要它：在此之前「打开文件」每次都要走一遍系统选择器（选目录 → 找文件 → 点确定），
 * 一个写笔记的人一天要走十几遍。授权一次之后，这个文件夹就成了应用内的常驻入口。
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
    modifier: Modifier = Modifier,
    refreshTick: Int = 0,
) {
    val scope = rememberCoroutineScope()
    val treeUri = viewModel.folderTree
    // 授权失效（卸载重装、用户在系统里撤销）与「从没选过」要说不同的话
    val everChosen = remember(treeUri) { treeUri == null && repository.folderTreeStored() != null }

    var entries by remember { mutableStateOf<List<FolderEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { picked ->
        if (picked != null) {
            scope.launch {
                // takePersistableUriPermission 要走系统 IPC，别放主线程
                withContext(Dispatchers.IO) { repository.persistFolderPermission(picked) }
                repository.setFolderTree(picked.toString())
                viewModel.onFolderChosen(picked.toString(), repository.folderRootName(picked).orEmpty())
            }
        }
    }

    val currentUri = viewModel.currentFolderUri
    val currentName = viewModel.currentFolderName

    // 换文件夹 / 进出子目录 / 从编辑器返回（refreshTick）都要重列一次。
    // key 用**当前 Uri** 而不是路径深度：同深度换到另一个目录时也必须重列
    LaunchedEffect(currentUri, refreshTick) {
        val tree = treeUri ?: return@LaunchedEffect
        val folder = currentUri ?: return@LaunchedEffect
        loading = true
        entries = repository.listFolder(Uri.parse(tree), Uri.parse(folder))
        loading = false
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
                IconButton(onClick = { picker.launch(null) }) {
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
                onPick = { picker.launch(null) },
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
                    )
                }
            }
        }
    }
}

/** 文件夹里的一行：目录可进、文件可开，都用同一个点击语义 */
@Composable
private fun FolderRow(entry: FolderEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
