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

    val everChosen = remember(treeUri) { treeUri == null && repository.folderTreeStored() != null }

    var entries by remember { mutableStateOf<List<FolderEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    var renameTarget by remember { mutableStateOf<FolderEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<FolderEntry?>(null) }

    val currentUri = viewModel.currentFolderUri
    val currentName = viewModel.currentFolderName

    LaunchedEffect(currentUri, refreshTick, showHiddenFiles, viewModel.folderRevision) {
        val tree = treeUri ?: return@LaunchedEffect
        val folder = currentUri ?: return@LaunchedEffect
        loading = true
        entries = repository.listFolder(Uri.parse(tree), Uri.parse(folder), showHiddenFiles)
        loading = false
    }

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

    renameTarget?.let { entry ->
        NameInputDialog(
            title = stringResource(R.string.rename),
            initial = entry.name,
            confirmLabel = stringResource(R.string.rename),
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                renameTarget = null

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
