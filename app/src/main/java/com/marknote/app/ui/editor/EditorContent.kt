package com.marknote.app.ui.editor

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Toc
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marknote.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorTopBar(
    title: String,
    usable: Boolean,
    charCount: Int,
    lineCount: Int,
    isPreview: Boolean,
    autoSave: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    hasUnsavedChanges: Boolean,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: () -> Unit,
    onToggleSearch: () -> Unit,
    onOpenOutline: () -> Unit,
    onTogglePreview: () -> Unit,
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = title,
                    maxLines = 1,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (usable) {
                    Text(
                        text = stringResource(R.string.stats_format, charCount, lineCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag("editor_back_button"),
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                )
            }
        },
        actions = {
            if (usable && !isPreview) {
                IconButton(onClick = onUndo, enabled = canUndo) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Undo,
                        contentDescription = stringResource(R.string.undo),
                    )
                }
                IconButton(onClick = onRedo, enabled = canRedo) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Redo,
                        contentDescription = stringResource(R.string.redo),
                    )
                }
            }
            if (usable && !isPreview && !autoSave) {
                IconButton(
                    onClick = onSave,
                    modifier = Modifier.testTag("editor_save_button"),
                ) {
                    Icon(
                        Icons.Outlined.Save,
                        contentDescription = stringResource(R.string.save),
                        tint = if (hasUnsavedChanges) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            if (usable) {
                IconButton(
                    onClick = onToggleSearch,
                    modifier = Modifier.testTag("editor_search_button"),
                ) {
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = stringResource(R.string.search),
                    )
                }
                IconButton(
                    onClick = onOpenOutline,
                    modifier = Modifier.testTag("editor_outline_button"),
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Toc,
                        contentDescription = stringResource(R.string.outline),
                    )
                }
                IconButton(
                    onClick = onTogglePreview,
                    modifier = Modifier.testTag("editor_preview_toggle"),
                ) {
                    Icon(
                        imageVector = if (isPreview) Icons.Outlined.Edit else Icons.Outlined.Visibility,
                        contentDescription = stringResource(
                            if (isPreview) R.string.edit else R.string.preview,
                        ),
                    )
                }
            }
        },
    )
}

@Composable
internal fun EditorEditBody(
    content: TextFieldValue,
    editorFontSp: Int,
    notice: EditorNotice?,
    search: EditorSearchState,
    visualTransformation: VisualTransformation,
    onContentChange: (TextFieldValue) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onRegrant: () -> Unit,
    onStepMatch: (Int) -> Unit,
    onReplaceCurrent: () -> Unit,
    onReplaceAll: () -> Int,
) {
    val context = LocalContext.current
    val matches = search.matches(isPreview = false)
    val clampedIndex = search.clampedIndex(isPreview = false)

    Column(
        modifier = Modifier
            .widthIn(max = 840.dp)
            .fillMaxWidth(),
    ) {
        if (notice != null) {
            DocumentNotice(
                message = stringResource(
                    when (notice) {
                        EditorNotice.SAVE_FAILED -> R.string.notice_save_failed
                        EditorNotice.READ_ONLY -> R.string.notice_read_only
                        EditorNotice.NO_PERSISTED_ACCESS -> R.string.notice_no_persistent_access
                    },
                ),
                actionLabel = stringResource(R.string.regrant),
                onAction = onRegrant,
            )
        }
        if (search.isOpen) {
            SearchPanel(
                query = search.query,
                onQueryChange = search::updateQuery,
                matchCase = search.matchCase,
                onToggleMatchCase = search::toggleMatchCase,
                replacement = search.replacement,
                onReplacementChange = search::updateReplacement,
                matchCount = matches.size,
                matchIndex = clampedIndex,
                onPrev = { onStepMatch(-1) },
                onNext = { onStepMatch(1) },
                onReplace = onReplaceCurrent,
                onReplaceAll = {
                    val count = onReplaceAll()
                    if (count > 0) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.replaced_count, count),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                onClose = search::close,
            )
        }
        TextField(
            value = content,
            onValueChange = onContentChange,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("editor_text_field")
                .onFocusChanged { onFocusChange(it.isFocused) },
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = editorFontSp.sp,
                lineHeight = (editorFontSp * 1.45f).sp,
            ),
            placeholder = { Text(stringResource(R.string.editor_placeholder)) },
            visualTransformation = visualTransformation,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )
    }
}

@Composable
internal fun EditorPreviewBody(
    markdown: String,
    previewFontSp: Int,
    docUri: String,
    imageTree: String?,
    showImageFolderBanner: Boolean,
    search: EditorSearchState,
    scrollTo: PreviewScroll?,
    onGrantImageFolder: () -> Unit,
    onStepMatch: (Int) -> Unit,
    onRenderedText: (String) -> Unit,
    onAnchorClick: (String) -> Unit,
    onToggleTask: (Int) -> Unit,
) {
    val matches = search.matches(isPreview = true)
    val clampedIndex = search.clampedIndex(isPreview = true)

    Column(
        modifier = Modifier
            .widthIn(max = 840.dp)
            .fillMaxWidth()
            .imePadding(),
    ) {
        if (search.isOpen) {
            SearchPanel(
                query = search.query,
                onQueryChange = search::updateQuery,
                matchCase = search.matchCase,
                onToggleMatchCase = search::toggleMatchCase,
                matchCount = matches.size,
                matchIndex = clampedIndex,
                onPrev = { onStepMatch(-1) },
                onNext = { onStepMatch(1) },
                onClose = search::close,
            )
        }
        if (showImageFolderBanner) {
            ImageFolderBanner(onGrant = onGrantImageFolder)
        }
        MarkdownPreview(
            markdown = markdown,
            textSizeSp = previewFontSp,
            docUri = docUri,
            imageTree = imageTree,
            highlights = matches,
            currentHighlight = search.currentMatch(isPreview = true),
            scrollTo = scrollTo,
            onRenderedText = onRenderedText,
            onAnchorClick = onAnchorClick,
            onToggleTask = onToggleTask,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        )
    }
}

@Composable
internal fun UnsavedChangesDialog(
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onStay: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onStay,
        title = { Text(stringResource(R.string.unsaved_changes_title)) },
        text = { Text(stringResource(R.string.unsaved_changes_message)) },
        confirmButton = {
            TextButton(onClick = onSave) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard) {
                    Text(stringResource(R.string.discard_changes))
                }
                TextButton(onClick = onStay) {
                    Text(stringResource(R.string.stay_in_editor))
                }
            }
        },
    )
}

internal fun handleEditorKeyEvent(
    event: KeyEvent,
    isPreview: Boolean,
    editorFocused: Boolean,
    boldAction: MarkdownAction?,
    italicAction: MarkdownAction?,
    onIndent: (outdent: Boolean) -> Unit,
    onSave: () -> Unit,
    onToggleSearch: () -> Unit,
    onApplyAction: (MarkdownAction) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val editing = !isPreview
    if (event.key == Key.Tab) {
        if (!editing || !editorFocused) return false
        onIndent(event.isShiftPressed)
        return true
    }
    if (!event.isCtrlPressed) return false
    return when (event.key) {
        Key.S -> {
            if (!editing) return false
            onSave()
            true
        }
        Key.F -> {
            onToggleSearch()
            true
        }
        Key.B -> {
            if (!editing) return false
            boldAction?.let(onApplyAction)
            true
        }
        Key.I -> {
            if (!editing) return false
            italicAction?.let(onApplyAction)
            true
        }
        Key.Z -> {
            if (!editing) return false
            if (event.isShiftPressed) onRedo() else onUndo()
            true
        }
        Key.Y -> {
            if (!editing) return false
            onRedo()
            true
        }
        else -> false
    }
}

@Composable
private fun ImageFolderBanner(onGrant: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 4.dp),
        ) {
            Icon(
                Icons.Outlined.Image,
                contentDescription = null,
                modifier = Modifier.width(18.dp),
            )
            Text(
                stringResource(R.string.image_folder_hint),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            )
            TextButton(onClick = onGrant) {
                Text(stringResource(R.string.grant_image_folder))
            }
        }
    }
}
