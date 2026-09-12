package com.marknote.app.ui.files

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuOpen
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.marknote.app.data.DocumentMeta
import com.marknote.app.data.DocumentRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileListScreen(
    repository: DocumentRepository,
    onOpenDocument: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onCollapse: (() -> Unit)? = null,
    refreshTick: Int = 0,
) {
    val viewModel: FileListViewModel = viewModel(key = "file-list") {
        FileListViewModel(repository)
    }

    // 从编辑器返回后刷新列表（摘要/时间可能已变化）
    LaunchedEffect(refreshTick) {
        if (refreshTick > 0) viewModel.refresh()
    }

    var pendingRemove by remember { mutableStateOf<DocumentMeta?>(null) }

    // 系统文档选择器：打开已有文件
    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.onDocumentPicked(uri, onOpenDocument)
    }

    // 系统文档选择器：在任意目录新建 .md 文件
    val createLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        if (uri != null) viewModel.onDocumentPicked(uri, onOpenDocument)
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("MarkNote") },
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "设置")
                    }
                    if (onCollapse != null) {
                        IconButton(onClick = onCollapse) {
                            Icon(
                                Icons.AutoMirrored.Outlined.MenuOpen,
                                contentDescription = "收起侧栏",
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SmallFloatingActionButton(
                    onClick = { createLauncher.launch("未命名.md") },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "新建文件")
                }
                SmallFloatingActionButton(
                    onClick = {
                        openLauncher.launch(arrayOf("text/markdown", "text/plain", "*/*"))
                    },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = "打开文件")
                }
            }
        },
    ) { padding ->
        if (viewModel.documents.isEmpty() && !viewModel.isLoading) {
            EmptyState(Modifier.padding(padding))
        } else {
            // 宽屏（≥600dp）用自适应网格，窄屏单列列表
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                val wide = maxWidth >= 600.dp
                if (wide) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 340.dp),
                        // 底部留出 FAB 区域，避免卡片被遮挡
                        contentPadding = PaddingValues(
                            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 176.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(viewModel.documents, key = { it.uri }) { doc ->
                            DocumentCard(
                                doc = doc,
                                timeText = viewModel.formatTime(doc.openedAt),
                                onClick = { onOpenDocument(doc.uri) },
                                onRemove = { pendingRemove = doc },
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(
                            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 176.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(viewModel.documents, key = { it.uri }) { doc ->
                            DocumentCard(
                                doc = doc,
                                timeText = viewModel.formatTime(doc.openedAt),
                                onClick = { onOpenDocument(doc.uri) },
                                onRemove = { pendingRemove = doc },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingRemove?.let { doc ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("从列表移除") },
            text = { Text("将「${doc.name}」从最近打开列表中移除，不会删除设备上的文件。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemove = null
                    viewModel.remove(doc.uri)
                }) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun DocumentCard(
    doc: DocumentMeta,
    timeText: String,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
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
                    !doc.accessible ->
                        "文件暂不可访问：可能已被移动或删除，或访问权限已失效。点按可重新授权。"
                    doc.snippet.isNotEmpty() -> doc.snippet
                    else -> "（空文档）"
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
                        contentDescription = "从列表移除",
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("还没有打开过文件", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "点击「打开文件」选择设备上的 Markdown 文档\n也可以在文件管理器里用 MarkNote 直接打开 .md 文件",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
