package com.marknote.app.ui.editor

import android.net.Uri
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
) {
    val viewModel: EditorViewModel = viewModel(key = "editor-$uriString") {
        EditorViewModel(repository, uriString)
    }

    // ViewModel 挂在 Activity 上、关掉编辑器不会销毁它，重开同一份文档会命中同一个实例，
    // 于是 init 里的读取不会重跑。这里在每次进入编辑器时补一次同步：磁盘内容若已被外部
    // 改过且本地没有未保存改动，就用磁盘版本刷新，避免旧内容被自动保存写回去。
    LaunchedEffect(uriString) {
        viewModel.syncFromDiskIfClean()
    }

    // 重新授权：内容读不出来（权限失效）或只读打开时，用系统文档选择器重选该文件。
    // 选择器返回的 Uri 一定能持久化，因此重选一次后可长期编辑；授权后替换最近列表里的旧条目。
    val regrantUri = remember(uriString) { Uri.parse(uriString) }
    val scope = rememberCoroutineScope()
    val regrantLauncher = rememberLauncherForActivityResult(
        remember(regrantUri) { OpenDocumentWithInitialUri(regrantUri) },
    ) { picked ->
        if (picked != null) {
            // 这两步都要跨进程问 provider（persistPermission 是 IPC、replaceRecent 内部要查显示名），
            // 与文件列表那条路同一个理由：放 IO 做，别让回调所在的主线程被 provider 的响应时间拖住
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
        regrantLauncher.launch(arrayOf("text/markdown", "text/plain", "*/*"))
    }

    // 自动保存：内容变化后停顿 800ms 落盘（可在设置中关闭，改为手动保存）
    LaunchedEffect(viewModel.content.text, settings.autoSave) {
        if (!viewModel.isLoaded || !settings.autoSave) return@LaunchedEffect
        delay(800)
        viewModel.save()
    }

    BackHandler {
        viewModel.save()
        onBack()
    }

    val title = displayName.removeSuffix(".md").removeSuffix(".markdown")
    // 读取失败时只提供错误提示，不进入编辑/预览（避免空内容被误写回原文件）
    val usable = viewModel.isLoaded

    // 语法高亮：高亮器只在主题色变化时重建（它内部那 10 条正则是 lazy 编译的，重建 = 全部重编，
    // 早先按正文做 key 等于每敲一键重编一遍），正文变化只重跑扫描
    val colorScheme = MaterialTheme.colorScheme
    val highlighter = remember(colorScheme) { MarkdownHighlighter(colorScheme) }
    val highlighted = remember(viewModel.content.text, highlighter) {
        highlighter.highlight(viewModel.content.text)
    }
    val highlightTransformation = remember(highlighted) {
        VisualTransformation { TransformedText(highlighted, OffsetMapping.Identity) }
    }

    var showOutline by remember { mutableStateOf(false) }

    // 大纲：只在面板打开时才算。解析是 O(全文) 的，而它唯一的用处就是那个面板
    // （两处渲染都判了 showOutline），按内容缓存挡不住「每敲一键重算一次」，纯属白烧。
    val outline = remember(viewModel.content.text, showOutline) {
        if (showOutline) parseOutline(viewModel.content.text) else emptyList()
    }

    // 图片文件夹授权：授权后相对路径图片可在预览中显示。
    // 记在 prefs 里的 tree 串不代表授权还在（重装、用户撤销、系统回收都会让它失效），
    // 失效时一律按「没授权」处理，好让引导条重新露出来，否则只会静默显示破图。
    //
    // 初值要读 prefs 再跨进程问一次授权，不能放在组合里同步做（会拖住主线程），所以改成异步取。
    // 取回来之前 imageTree 是 null，用 imageTreeResolved 把已经授权过的文档挡在引导条之外，
    // 否则每次进已授权的文档都会先闪一下「去授权」。
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
            // 选择器刚授过权、本进程内立刻可用，所以先更新界面让预览马上能显示图片；
            // 落库那两步（IPC + prefs 读改写）挪到 IO
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

    // 搜索替换。编辑器在源文本上搜索；预览没有源文本可编辑，改为在**渲染结果**上搜索，
    // 命中与所见一致（命中数由 MarkdownPreview 回传渲染文本后算出）。
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var matchIndex by remember { mutableStateOf(0) }
    // 预览定位：同一个位置可能需要重复滚动（关掉搜索再点同一项），用 nonce 触发
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

    // 编辑器：输入变化后在重组完成时跳到第一个命中（此时 matches 已是新 query 的结果）
    LaunchedEffect(query) {
        if (!viewModel.isPreview && query.isNotEmpty() && sourceMatches.isNotEmpty()) jumpToMatch(0)
    }

    // 预览：命中数要等渲染文本回传才知道，拿到之后跳到第一个命中
    LaunchedEffect(viewModel.isPreview, query, renderedMatches.size) {
        if (viewModel.isPreview && query.isNotEmpty() && renderedMatches.isNotEmpty()) jumpToMatch(0)
    }

    // 大纲跳转：预览态下滚动到对应标题（下标在渲染文本里），编辑器里则是移动光标
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

    // 显示用的命中序号：内容变化后命中数可能变少，避免 n/m 里的 n 越界
    val clampedMatchIndex = matchIndex.coerceIn(0, (matches.size - 1).coerceAtLeast(0))

    // 字数统计：每次重组都遍历全文太浪费（光标移动、搜索、滚动都会触发重组），按内容缓存
    val (charCount, lineCount) = remember(viewModel.content.text) { viewModel.stats() }

    Scaffold(
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
                    IconButton(onClick = {
                        viewModel.save()
                        onBack()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    // 撤销/重做只在编辑态出现：预览是只读视图，没有可撤销的操作；隐藏掉还能
                    // 把顶栏宽度让给文件名（411dp 的机器上六个图标已经很挤）。
                    //
                    // 图标不指定 tint —— TopAppBar 通过 LocalContentColor 给的是
                    // onSurfaceVariant，而 IconButton 在 disabled 时会把它降到 38% 透明度。
                    // 一旦写死 tint，按钮就永远是同一个颜色，撤到底也看不出来。
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
                    // 手动保存：关闭自动保存时显示；有未保存修改时高亮（预览态无需保存）
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
                        // 搜索与大纲在编辑、预览下都可用（预览是只读视图，同样需要查找与导航）
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
        // 大屏限宽居中：内容最大 840dp，两侧留白
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
                    // 搜索时要弹键盘，预览没有底栏，得自己避让，否则命中被键盘挡住
                    .imePadding(),
            ) {
                // 只读预览同样支持搜索：只找位置不改内容，所以不传替换回调（面板就不渲染替换行）
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
                // 文档含相对路径图片但尚未授权图片文件夹时，显示一次性引导条
                // （imageTreeResolved 之前不显示：异步查询还没回来，先别急着让用户去授权）
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
                    markdown = viewModel.content.text,
                    textSizeSp = settings.previewFontSp,
                    docUri = uriString,
                    imageTree = imageTree,
                    highlights = matches,
                    currentHighlight = matches.getOrNull(clampedMatchIndex),
                    scrollTo = scrollTo,
                    onRenderedText = { renderedText = it },
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
                // 只能读 / 写盘失败：明确提示，避免用户以为改动已保存
                if (viewModel.readOnly || viewModel.saveFailed) {
                    DocumentNotice(
                        message = stringResource(
                            if (viewModel.saveFailed) R.string.notice_save_failed
                            else R.string.notice_read_only,
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
                                // 替换后文本已变，直接选中刚写入的新内容
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
                // 纯源码编辑：等宽字体、无边框、全屏
                //
                // 这里不挂硬件键盘的 Ctrl+Z/Ctrl+Y：实测过，文本框有焦点时字母按键会先交给
                // 输入法，应用窗口（Activity.dispatchKeyEvent、View.onKeyPreIme、Compose 的
                // onPreviewKeyEvent）全都收不到 Z 的 KeyDown。Gboard 自带逐字符撤销，会把
                // Ctrl+Z 吃掉——用户看到的「撤销」是它的，不是我们栈的。顶栏那两个按钮才是
                // 真正可用的入口，详见 README 的说明。
                TextField(
                    value = viewModel.content,
                    onValueChange = viewModel::onContentChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
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

        // 宽屏：大纲以右侧面板呈现，与左侧文件列表栏风格一致（预览态也可调出）
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

    // 窄屏：大纲从底部弹出（预览态也可调出）
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
}
