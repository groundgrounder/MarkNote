package com.marknote.app.ui.editor

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.marknote.app.R
import com.marknote.app.data.DocumentRepository
import com.marknote.app.data.SettingsRepository
import com.marknote.app.ui.common.OpenDocumentWithInitialUri
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    repository: DocumentRepository,
    settings: SettingsRepository,
    uriString: String,
    displayName: String,
    onBack: () -> Unit,
    isExpanded: Boolean = false,
    onRelocated: (String) -> Unit = {},
    settingsRequestTick: Int = 0,
    onSettingsOpened: () -> Unit = {},
) {
    val viewModel: EditorViewModel = viewModel(key = "editor-$uriString") {
        EditorViewModel(repository, uriString)
    }

    LaunchedEffect(uriString) {
        viewModel.syncFromDiskIfClean()
    }

    val regrantUri = remember(uriString) { Uri.parse(uriString) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val regrantLauncher = rememberLauncherForActivityResult(
        remember(regrantUri) { OpenDocumentWithInitialUri(regrantUri) },
    ) { picked ->
        if (picked != null) {

            scope.launch {
                withContext(Dispatchers.IO) {
                    repository.persistPermission(picked)
                    repository.replaceRecent(uriString, picked)
                }
                onRelocated(picked.toString())
            }
        }
    }

    val regrant: () -> Unit = {
        regrantLauncher.launch(arrayOf("*/*"))
    }

    LaunchedEffect(viewModel.content.text, settings.autoSave) {
        if (!viewModel.isLoaded || !settings.autoSave) return@LaunchedEffect
        delay(800)
        viewModel.save()
    }

    var pendingExit by remember { mutableStateOf(false) }

    var discardOnExit by remember { mutableStateOf(false) }
    var pendingExitAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun requestExit(afterExit: () -> Unit = onBack) {
        if (settings.autoSave || !viewModel.isLoaded || !viewModel.hasUnsavedChanges) {
            viewModel.save()
            afterExit()
        } else {
            pendingExitAction = afterExit
            pendingExit = true
        }
    }

    LaunchedEffect(settingsRequestTick) {
        if (settingsRequestTick > 0) requestExit(onSettingsOpened)
    }

    val latestAutoSave by rememberUpdatedState(settings.autoSave)
    val latestDiscardOnExit by rememberUpdatedState(discardOnExit)
    DisposableEffect(viewModel) {
        onDispose { if (latestAutoSave && !latestDiscardOnExit) viewModel.save() }
    }

    BackHandler { requestExit() }

    val title = fileTitle(displayName)

    val usable = viewModel.isLoaded

    val colorScheme = MaterialTheme.colorScheme
    val highlighter = remember(colorScheme) { MarkdownHighlighter(colorScheme) }
    val sourceText = viewModel.content.text
    var highlighted by remember(sourceText, highlighter) { mutableStateOf(highlighter.highlight("")) }
    LaunchedEffect(sourceText, highlighter) {
        delay(documentWorkDelay(sourceText.length))
        val result = withContext(Dispatchers.Default) { highlighter.highlight(sourceText) }
        highlighted = result
    }
    val highlightTransformation = remember(highlighted) {
        VisualTransformation { TransformedText(highlighted, OffsetMapping.Identity) }
    }

    var showOutline by remember { mutableStateOf(false) }

    var outline by remember(sourceText) { mutableStateOf(emptyList<Heading>()) }
    LaunchedEffect(sourceText, showOutline) {
        val result = if (showOutline) {
            withContext(Dispatchers.Default) { parseOutline(sourceText) }
        } else {
            emptyList()
        }
        outline = result
    }

    var previewMarkdown by remember(uriString) { mutableStateOf("") }
    LaunchedEffect(sourceText, viewModel.isPreview) {
        if (!viewModel.isPreview) return@LaunchedEffect
        delay(previewWorkDelay(sourceText.length))
        previewMarkdown = sourceText
    }

    var imageTree by remember(uriString) { mutableStateOf<String?>(null) }
    var imageTreeResolved by remember(uriString) { mutableStateOf(false) }
    LaunchedEffect(uriString) {
        imageTree = withContext(Dispatchers.IO) {
            repository.imageTreeFor(uriString)
                ?.takeIf { repository.hasPersistedPermission(Uri.parse(it)) }
        }
        imageTreeResolved = true
    }
    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { tree ->
        if (tree != null) {

            imageTree = tree.toString()
            imageTreeResolved = true
            scope.launch {
                withContext(Dispatchers.IO) {
                    repository.persistPermission(tree)
                    repository.setImageTree(uriString, tree.toString())
                }
            }
        }
    }

    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var matchIndex by remember { mutableStateOf(0) }

    var locateNonce by remember { mutableIntStateOf(0) }
    var scrollTo by remember { mutableStateOf<PreviewScroll?>(null) }
    var renderedText by remember(uriString) { mutableStateOf("") }

    val sourceMatches = remember(viewModel.content.text, query) {
        findMatches(viewModel.content.text, query)
    }
    val renderedMatches = remember(renderedText, query) { findMatches(renderedText, query) }
    val matches = if (viewModel.isPreview) renderedMatches else sourceMatches

    fun jumpToMatch(index: Int) {
        if (matches.isEmpty()) return
        matchIndex = ((index % matches.size) + matches.size) % matches.size
        val match = matches[matchIndex]
        if (viewModel.isPreview) {
            scrollTo = PreviewScroll(match.first, ++locateNonce)
        } else {
            viewModel.selectRange(match.first, match.last + 1)
        }
    }

    LaunchedEffect(query) {
        if (!viewModel.isPreview && query.isNotEmpty() && sourceMatches.isNotEmpty()) jumpToMatch(0)
    }

    LaunchedEffect(viewModel.isPreview, query, renderedMatches.size) {
        if (viewModel.isPreview && query.isNotEmpty() && renderedMatches.isNotEmpty()) jumpToMatch(0)
    }

    val jumpToHeading: (Int) -> Unit = { offset ->
        if (viewModel.isPreview) {
            scrollTo = PreviewScroll(
                offset = renderedOffsetOfHeading(
                    headingOffset = offset,
                    sourceText = viewModel.content.text,
                    renderedText = renderedText,
                ),
                nonce = ++locateNonce,
            )
        } else {
            viewModel.jumpTo(offset)
        }
    }

    val onAnchorClick: (String) -> Unit = { anchor ->
        val heading = findHeadingByAnchor(parseOutline(viewModel.content.text), anchor)
        if (heading != null) {
            jumpToHeading(heading.offset)
        } else {
            Toast.makeText(context, R.string.anchor_not_found, Toast.LENGTH_SHORT).show()
        }
    }

    var editorFocused by remember { mutableStateOf(false) }

    val boldAction = markdownActionByLabel("B")
    val italicAction = markdownActionByLabel("I")

    val onEditorKey: (KeyEvent) -> Boolean = keyHandler@{ event ->
        if (event.type != KeyEventType.KeyDown) return@keyHandler false

        val editing = !viewModel.isPreview
        if (event.key == Key.Tab) {
            if (!editing || !editorFocused) return@keyHandler false
            val selection = viewModel.content.selection
            val edit = indentLines(
                text = viewModel.content.text,
                selectionStart = selection.min,
                selectionEnd = selection.max,
                outdent = event.isShiftPressed,
            )
            if (edit != null) viewModel.applyEdit(edit)

            true
        } else if (event.isCtrlPressed) {
            when (event.key) {
                Key.S -> {
                    if (!editing) return@keyHandler false
                    viewModel.save()
                    true
                }
                Key.F -> {
                    searchOpen = !searchOpen
                    true
                }
                Key.B -> {
                    if (!editing) return@keyHandler false
                    boldAction?.let { viewModel.applyAction(it) }
                    true
                }
                Key.I -> {
                    if (!editing) return@keyHandler false
                    italicAction?.let { viewModel.applyAction(it) }
                    true
                }

                Key.Z -> {
                    if (!editing) return@keyHandler false
                    if (event.isShiftPressed) viewModel.redo() else viewModel.undo()
                    true
                }
                Key.Y -> {
                    if (!editing) return@keyHandler false
                    viewModel.redo()
                    true
                }
                else -> false
            }
        } else {
            false
        }
    }

    val clampedMatchIndex = matchIndex.coerceIn(0, (matches.size - 1).coerceAtLeast(0))

    var documentStats by remember(uriString) { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(sourceText) {
        delay(documentWorkDelay(sourceText.length))
        documentStats = withContext(Dispatchers.Default) { viewModel.stats(sourceText) }
    }
    val (charCount, lineCount) = documentStats ?: (0 to 0)

    Scaffold(

        modifier = Modifier.onPreviewKeyEvent(onEditorKey),
        topBar = {
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
                    IconButton(onClick = { requestExit() }) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {

                    if (usable && !viewModel.isPreview) {
                        IconButton(onClick = { viewModel.undo() }, enabled = viewModel.canUndo) {
                            Icon(
                                Icons.AutoMirrored.Outlined.Undo,
                                contentDescription = stringResource(R.string.undo),
                            )
                        }
                        IconButton(onClick = { viewModel.redo() }, enabled = viewModel.canRedo) {
                            Icon(
                                Icons.AutoMirrored.Outlined.Redo,
                                contentDescription = stringResource(R.string.redo),
                            )
                        }
                    }

                    if (usable && !viewModel.isPreview && !settings.autoSave) {
                        IconButton(onClick = { viewModel.save() }) {
                            Icon(
                                Icons.Outlined.Save,
                                contentDescription = stringResource(R.string.save),
                                tint = if (viewModel.hasUnsavedChanges) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                    if (usable) {

                        IconButton(onClick = {
                            searchOpen = !searchOpen
                            if (!searchOpen) { query = ""; replacement = "" }
                        }) {
                            Icon(
                                Icons.Outlined.Search,
                                contentDescription = stringResource(R.string.search),
                            )
                        }
                        IconButton(onClick = { showOutline = true }) {
                            Icon(
                                Icons.AutoMirrored.Outlined.Toc,
                                contentDescription = stringResource(R.string.outline),
                            )
                        }
                        IconButton(onClick = { viewModel.togglePreview() }) {
                            Icon(
                                imageVector = if (viewModel.isPreview) Icons.Outlined.Edit
                                else Icons.Outlined.Visibility,
                                contentDescription = stringResource(
                                    if (viewModel.isPreview) R.string.edit else R.string.preview,
                                ),
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (usable && !viewModel.isPreview) {
                MarkdownToolbar(
                    onAction = viewModel::applyAction,
                    modifier = Modifier
                        .navigationBarsPadding()
                        .imePadding(),
                )
            }
        },
    ) { padding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {

        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.TopCenter,
        ) {
        if (!usable) {
            DocumentUnavailable(
                onRegrant = regrant,
                onRetry = viewModel::load,
                onBack = onBack,
            )
        } else if (viewModel.isPreview) {
            Column(
                modifier = Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxWidth()

                    .imePadding(),
            ) {

                if (searchOpen) {
                    SearchPanel(
                        query = query,
                        onQueryChange = {
                            query = it
                            matchIndex = 0
                        },
                        matchCount = matches.size,
                        matchIndex = clampedMatchIndex,
                        onPrev = { jumpToMatch(matchIndex - 1) },
                        onNext = { jumpToMatch(matchIndex + 1) },
                        onClose = {
                            searchOpen = false
                            query = ""
                            replacement = ""
                        },
                    )
                }

                if (imageTreeResolved && imageTree == null && hasRelativeImage(viewModel.content.text)) {
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
                            TextButton(onClick = { treeLauncher.launch(null) }) {
                                Text(stringResource(R.string.grant_image_folder))
                            }
                        }
                    }
                }
                MarkdownPreview(
                    markdown = previewMarkdown,
                    textSizeSp = settings.previewFontSp,
                    docUri = uriString,
                    imageTree = imageTree,
                    highlights = matches,
                    currentHighlight = matches.getOrNull(clampedMatchIndex),
                    scrollTo = scrollTo,
                    onRenderedText = { renderedText = it },
                    onAnchorClick = onAnchorClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxWidth(),
            ) {

                val notice = editorNoticeOf(
                    saveFailed = viewModel.saveFailed,
                    readOnly = viewModel.readOnly,
                    hasPersistedAccess = viewModel.hasPersistedAccess,
                )
                if (notice != null) {
                    DocumentNotice(
                        message = stringResource(
                            when (notice) {
                                EditorNotice.SAVE_FAILED -> R.string.notice_save_failed
                                EditorNotice.READ_ONLY -> R.string.notice_read_only
                                EditorNotice.NO_PERSISTED_ACCESS ->
                                    R.string.notice_no_persistent_access
                            },
                        ),
                        actionLabel = stringResource(R.string.regrant),
                        onAction = regrant,
                    )
                }
                if (searchOpen) {
                    SearchPanel(
                        query = query,
                        onQueryChange = {
                            query = it
                            matchIndex = 0
                        },
                        replacement = replacement,
                        onReplacementChange = { replacement = it },
                        matchCount = matches.size,
                        matchIndex = clampedMatchIndex,
                        onPrev = { jumpToMatch(matchIndex - 1) },
                        onNext = { jumpToMatch(matchIndex + 1) },
                        onReplace = {
                            if (matches.isNotEmpty()) {
                                val start = matches[clampedMatchIndex].first
                                viewModel.replaceInRange(
                                    start,
                                    start + query.length,
                                    replacement,
                                )

                                viewModel.selectRange(start, start + replacement.length)
                            }
                        },
                        onReplaceAll = {
                            viewModel.replaceAll(query, replacement)
                        },
                        onClose = {
                            searchOpen = false
                            query = ""
                            replacement = ""
                        },
                    )
                }

                TextField(
                    value = viewModel.content,
                    onValueChange = viewModel::onContentChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .onFocusChanged { editorFocused = it.isFocused },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = settings.editorFontSp.sp,
                    ),
                    placeholder = { Text(stringResource(R.string.editor_placeholder)) },
                    visualTransformation = highlightTransformation,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
            }
        }
        }

        if (isExpanded && showOutline) {
            VerticalDivider()
            OutlinePanel(
                outline = outline,
                onJump = { offset ->
                    showOutline = false
                    jumpToHeading(offset)
                },
                onClose = { showOutline = false },
                modifier = Modifier
                    .width(320.dp)
                    .fillMaxHeight(),
            )
        }
        }
    }

    if (!isExpanded && showOutline) {
        OutlineSheet(
            outline = outline,
            onJump = { offset ->
                showOutline = false
                jumpToHeading(offset)
            },
            onDismiss = { showOutline = false },
        )
    }

    if (pendingExit) {
        AlertDialog(
            onDismissRequest = { pendingExit = false },
            title = { Text(stringResource(R.string.unsaved_changes_title)) },
            text = { Text(stringResource(R.string.unsaved_changes_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingExit = false
                        viewModel.save()
                        pendingExitAction?.invoke()
                        pendingExitAction = null
                        pendingExit = false
                    },
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            discardOnExit = true
                            pendingExitAction?.invoke()
                            pendingExitAction = null
                            pendingExit = false
                        },
                    ) { Text(stringResource(R.string.discard_changes)) }
                    TextButton(onClick = { pendingExit = false }) {
                        Text(stringResource(R.string.stay_in_editor))
                    }
                }
            },
        )
    }
}

private fun documentWorkDelay(length: Int): Long = when {
    length >= 500_000 -> 160L
    length >= 100_000 -> 80L
    else -> 16L
}

private fun previewWorkDelay(length: Int): Long = when {
    length >= 500_000 -> 400L
    length >= 100_000 -> 250L
    else -> 150L
}
