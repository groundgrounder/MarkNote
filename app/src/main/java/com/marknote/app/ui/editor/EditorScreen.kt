package com.marknote.app.ui.editor

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.marknote.app.AppNavigationTarget
import com.marknote.app.R
import com.marknote.app.data.DocumentRepository
import com.marknote.app.data.SettingsRepository
import com.marknote.app.ui.common.OpenDocumentWithInitialUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun EditorScreen(
    repository: DocumentRepository,
    settings: SettingsRepository,
    uriString: String,
    displayName: String,
    isExpanded: Boolean = false,
    pendingNavigation: AppNavigationTarget? = null,
    onNavigationConfirmed: (AppNavigationTarget) -> Unit = {},
    onNavigationCancelled: () -> Unit = {},
    onRelocated: (String) -> Unit = {},
) {
    // Reuse one EditorViewModel per window to avoid unbounded memory retention across documents.
    val viewModel: EditorViewModel = viewModel(key = "editor") {
        EditorViewModel(repository)
    }

    LaunchedEffect(uriString) {
        viewModel.open(uriString)
    }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val regrantUri = remember(uriString) { Uri.parse(uriString) }
    val regrantLauncher = rememberLauncherForActivityResult(
        remember(regrantUri) { OpenDocumentWithInitialUri(regrantUri) },
    ) { picked ->
        if (picked != null) viewModel.regrantDocument(picked, onRelocated)
    }
    val regrant: () -> Unit = { regrantLauncher.launch(arrayOf("*/*")) }

    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { tree ->
        if (tree != null) viewModel.grantImageTree(tree)
    }

    LaunchedEffect(viewModel.content.text, settings.autoSave, viewModel.loadState) {
        if (!viewModel.isLoaded || !settings.autoSave) return@LaunchedEffect
        delay(800)
        viewModel.save()
    }

    var dialogTarget by remember(uriString) { mutableStateOf<AppNavigationTarget?>(null) }
    var discardOnExit by remember(uriString) { mutableStateOf(false) }

    fun confirmNavigation(target: AppNavigationTarget, forceReset: Boolean = false) {
        if (forceReset || target !is AppNavigationTarget.OpenSettings) {
            viewModel.reset()
        }
        onNavigationConfirmed(target)
    }

    fun saveAndNavigate(target: AppNavigationTarget) {
        scope.launch {
            val saved = viewModel.saveAndWait()
            if (saved || !viewModel.hasUnsavedChanges) {
                confirmNavigation(target)
            } else {
                onNavigationCancelled()
            }
        }
    }

    fun requestNavigation(target: AppNavigationTarget) {
        if (!viewModel.isLoaded || !viewModel.hasUnsavedChanges) {
            confirmNavigation(target)
        } else if (settings.autoSave) {
            saveAndNavigate(target)
        } else {
            dialogTarget = target
        }
    }

    LaunchedEffect(pendingNavigation) {
        pendingNavigation?.let(::requestNavigation)
    }

    val latestAutoSave by rememberUpdatedState(settings.autoSave)
    val latestDiscardOnExit by rememberUpdatedState(discardOnExit)
    DisposableEffect(uriString) {
        onDispose { if (latestAutoSave && !latestDiscardOnExit) viewModel.save() }
    }

    BackHandler { requestNavigation(AppNavigationTarget.CloseEditor) }

    val usable = viewModel.isLoaded
    val sourceText = viewModel.content.text
    val colorScheme = MaterialTheme.colorScheme
    val highlighter = remember(colorScheme) { MarkdownHighlighter(colorScheme) }

    var highlightSpans by remember(uriString) { mutableStateOf<List<HighlightSpan>>(emptyList()) }
    LaunchedEffect(sourceText) {
        delay(documentWorkDelay(sourceText.length))
        highlightSpans = withContext(Dispatchers.Default) { highlightRanges(sourceText) }
    }

    var showOutline by remember(uriString) { mutableStateOf(false) }
    var outline by remember(uriString) { mutableStateOf<List<Heading>>(emptyList()) }
    LaunchedEffect(sourceText, showOutline) {
        outline = if (showOutline) {
            withContext(Dispatchers.Default) { parseOutline(sourceText) }
        } else {
            emptyList()
        }
    }

    var previewMarkdown by remember(uriString) { mutableStateOf("") }
    LaunchedEffect(sourceText, viewModel.isPreview) {
        if (viewModel.isPreview) previewMarkdown = sourceText
    }

    var documentStats by remember(uriString) { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(sourceText) {
        delay(documentWorkDelay(sourceText.length))
        documentStats = withContext(Dispatchers.Default) { viewModel.stats(sourceText) }
    }
    val (charCount, lineCount) = documentStats ?: (0 to 0)

    var renderedText by remember(uriString) { mutableStateOf("") }
    var locateNonce by remember(uriString) { mutableIntStateOf(0) }
    var scrollTo by remember(uriString) { mutableStateOf<PreviewScroll?>(null) }
    val search = rememberEditorSearchState(uriString, sourceText, renderedText)

    fun focusMatch(range: IntRange?) {
        if (range == null) return
        if (viewModel.isPreview) {
            scrollTo = PreviewScroll(range.first, ++locateNonce)
        } else {
            viewModel.selectRange(range.first, range.last + 1)
        }
    }

    LaunchedEffect(viewModel.isPreview, search.sourceMatchesRevision, search.sourceMatches) {
        if (!viewModel.isPreview) focusMatch(search.consumeAutoJump(isPreview = false))
    }

    LaunchedEffect(viewModel.isPreview, search.renderedMatchesRevision, search.renderedMatches) {
        if (viewModel.isPreview) focusMatch(search.consumeAutoJump(isPreview = true))
    }

    val currentSourceMatch = search.currentMatch(isPreview = false)
    val highlightTransformation = remember(
        highlighter,
        highlightSpans,
        search.isOpen,
        search.sourceMatches,
        currentSourceMatch,
    ) {
        val activeMatches = if (search.isOpen) search.sourceMatches else emptyList()
        val activeCurrent = if (search.isOpen) currentSourceMatch else null
        VisualTransformation { text ->
            TransformedText(
                highlighter.build(text.text, highlightSpans, activeMatches, activeCurrent),
                OffsetMapping.Identity,
            )
        }
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

    Scaffold(
        modifier = Modifier.onPreviewKeyEvent { event ->
            handleEditorKeyEvent(
                event = event,
                isPreview = viewModel.isPreview,
                editorFocused = editorFocused,
                boldAction = boldAction,
                italicAction = italicAction,
                onIndent = viewModel::indentSelection,
                onSave = viewModel::save,
                onToggleSearch = search::toggle,
                onApplyAction = viewModel::applyAction,
                onUndo = viewModel::undo,
                onRedo = viewModel::redo,
            )
        },
        topBar = {
            EditorTopBar(
                title = fileTitle(displayName),
                usable = usable,
                charCount = charCount,
                lineCount = lineCount,
                isPreview = viewModel.isPreview,
                autoSave = settings.autoSave,
                canUndo = viewModel.canUndo,
                canRedo = viewModel.canRedo,
                hasUnsavedChanges = viewModel.hasUnsavedChanges,
                onBack = { requestNavigation(AppNavigationTarget.CloseEditor) },
                onUndo = viewModel::undo,
                onRedo = viewModel::redo,
                onSave = viewModel::save,
                onToggleSearch = search::toggle,
                onOpenOutline = { showOutline = true },
                onTogglePreview = viewModel::togglePreview,
            )
        },
        bottomBar = {
            if (usable && !viewModel.isPreview) {
                MarkdownToolbar(
                    onAction = viewModel::applyAction,
                    onIndent = viewModel::indentSelection,
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
                when (val state = viewModel.loadState) {
                    DocumentLoadState.Loading -> DocumentLoading()
                    is DocumentLoadState.TooLarge -> DocumentTooLarge(
                        limitMb = state.limitMb,
                        onBack = { requestNavigation(AppNavigationTarget.CloseEditor) },
                    )
                    DocumentLoadState.Unavailable -> DocumentUnavailable(
                        onRegrant = regrant,
                        onRetry = viewModel::load,
                        onBack = { requestNavigation(AppNavigationTarget.CloseEditor) },
                    )
                    DocumentLoadState.Loaded -> if (viewModel.isPreview) {
                        EditorPreviewBody(
                            markdown = previewMarkdown,
                            previewFontSp = settings.previewFontSp,
                            docUri = uriString,
                            imageTree = viewModel.imageTree,
                            showImageFolderBanner = viewModel.imageTreeResolved &&
                                viewModel.imageTree == null &&
                                hasRelativeImage(sourceText),
                            search = search,
                            scrollTo = scrollTo,
                            onGrantImageFolder = { treeLauncher.launch(null) },
                            onStepMatch = { delta -> focusMatch(search.step(delta, isPreview = true)) },
                            onRenderedText = { renderedText = it },
                            onAnchorClick = onAnchorClick,
                            onToggleTask = viewModel::toggleTask,
                        )
                    } else {
                        EditorEditBody(
                            content = viewModel.content,
                            editorFontSp = settings.editorFontSp,
                            notice = editorNoticeOf(
                                saveFailed = viewModel.saveFailed,
                                readOnly = viewModel.readOnly,
                                hasPersistedAccess = viewModel.hasPersistedAccess,
                            ),
                            search = search,
                            visualTransformation = highlightTransformation,
                            onContentChange = viewModel::onContentChange,
                            onFocusChange = { editorFocused = it },
                            onRegrant = regrant,
                            onStepMatch = { delta -> focusMatch(search.step(delta, isPreview = false)) },
                            onReplaceCurrent = {
                                val match = search.currentMatch(isPreview = false) ?: return@EditorEditBody
                                val start = match.first
                                val query = search.query
                                if (query.isEmpty() || !sourceText.regionMatches(
                                        thisOffset = start,
                                        other = query,
                                        otherOffset = 0,
                                        length = query.length,
                                        ignoreCase = !search.matchCase,
                                    )
                                ) {
                                    return@EditorEditBody
                                }
                                viewModel.replaceInRange(start, start + query.length, search.replacement)
                                viewModel.selectRange(start, start + search.replacement.length)
                            },
                            onReplaceAll = {
                                viewModel.replaceAll(
                                    query = search.query,
                                    replacement = search.replacement,
                                    ignoreCase = !search.matchCase,
                                )
                            },
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

    val pending = dialogTarget
    if (pending != null) {
        UnsavedChangesDialog(
            onSave = {
                dialogTarget = null
                saveAndNavigate(pending)
            },
            onDiscard = {
                discardOnExit = true
                dialogTarget = null
                confirmNavigation(pending, forceReset = true)
            },
            onStay = {
                dialogTarget = null
                onNavigationCancelled()
            },
        )
    }
}

private fun documentWorkDelay(length: Int): Long = when {
    length >= 500_000 -> 160L
    length >= 100_000 -> 80L
    else -> 16L
}
