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

    LaunchedEffect(refreshTick) {
        if (refreshTick > 0) viewModel.refresh()
    }

    var pendingRemove by remember { mutableStateOf<DocumentMeta?>(null) }

    val mode = viewModel.listMode

    val context = LocalContext.current

    val scope = rememberCoroutineScope()
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { picked ->
        if (picked != null) {
            scope.launch {
                val rootName = withContext(Dispatchers.IO) {
                    if (!repository.persistFolderPermission(picked)) return@withContext null
                    repository.setFolderTree(picked.toString())
                    repository.folderRootName(picked).orEmpty()
                }
                if (rootName == null) {
                    Toast.makeText(context, R.string.folder_permission_failed, Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.onFolderChosen(picked.toString(), rootName)
                }
            }
        }
    }

    val createLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        if (uri != null) viewModel.onDocumentPicked(uri, onOpenDocument)
    }

    val untitledName = stringResource(R.string.untitled_md)

    var showNewFolderDialog by remember { mutableStateOf(false) }

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

            Box(
                modifier = Modifier
                    .size(40.dp)
                    .shadow(elevation = 6.dp, shape = MaterialTheme.shapes.medium)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .combinedClickable(
                        onClick = {
                            if (viewModel.listMode == LIST_MODE_FOLDER && viewModel.folderTree != null) {

                                createInCurrentFolder(untitledName, isFolder = false, openAfterwards = true)
                            } else {

                                createLauncher.launch(untitledName)
                            }
                        },
                        onLongClick = {

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

                    val listPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 8.dp, bottom = 176.dp,
                    )

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

