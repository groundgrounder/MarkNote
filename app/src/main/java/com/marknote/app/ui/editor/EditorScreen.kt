package com.marknote.app.ui.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.Toc
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.marknote.app.data.DocumentRepository
import com.marknote.app.data.SettingsRepository
import com.marknote.app.ui.common.OpenDocumentWithInitialUri
import kotlinx.coroutines.delay

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

    // 重新授权：内容读不出来（权限失效）或只读打开时，用系统文档选择器重选该文件。
    // 选择器返回的 Uri 一定能持久化，因此重选一次后可长期编辑；授权后替换最近列表里的旧条目。
    val regrantUri = remember(uriString) { Uri.parse(uriString) }
    val regrantLauncher = rememberLauncherForActivityResult(
        remember(regrantUri) { OpenDocumentWithInitialUri(regrantUri) },
    ) { picked ->
        if (picked != null) {
            repository.persistPermission(picked)
            repository.replaceRecent(uriString, picked)
            onRelocated(picked.toString())
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

    // 语法高亮：内容或主题变化时重算
    val colorScheme = MaterialTheme.colorScheme
    val highlighted = remember(viewModel.content.text, colorScheme) {
        MarkdownHighlighter(colorScheme).highlight(viewModel.content.text)
    }
    val highlightTransformation = remember(highlighted) {
        VisualTransformation { TransformedText(highlighted, OffsetMapping.Identity) }
    }

    // 大纲：内容变化时重新解析
    val outline = remember(viewModel.content.text) { parseOutline(viewModel.content.text) }
    var showOutline by remember { mutableStateOf(false) }

    // 图片文件夹授权：授权后相对路径图片可在预览中显示
    var imageTree by remember(uriString) { mutableStateOf(repository.imageTreeFor(uriString)) }
    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { tree ->
        if (tree != null) {
            repository.persistPermission(tree)
            repository.setImageTree(uriString, tree.toString())
            imageTree = tree.toString()
        }
    }

    // 搜索替换
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var matchIndex by remember { mutableStateOf(0) }
    val matches = remember(viewModel.content.text, query) {
        if (query.isEmpty()) emptyList() else buildList {
            var i = viewModel.content.text.indexOf(query)
            while (i >= 0) { add(i); i = viewModel.content.text.indexOf(query, i + 1) }
        }
    }
    fun jumpToMatch(index: Int) {
        if (matches.isEmpty()) return
        matchIndex = ((index % matches.size) + matches.size) % matches.size
        val start = matches[matchIndex]
        viewModel.selectRange(start, start + query.length)
    }

    // 输入变化后在重组完成时跳到第一个命中（此时 matches 已是新 query 的结果）
    LaunchedEffect(query) {
        if (query.isNotEmpty() && matches.isNotEmpty()) jumpToMatch(0)
    }

    // 字数统计
    val (charCount, lineCount) = viewModel.stats()

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
                                text = "$charCount 字 · $lineCount 行",
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
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (usable && !viewModel.isPreview) {
                        // 手动保存：关闭自动保存时显示；有未保存修改时高亮
                        if (!settings.autoSave) {
                            IconButton(onClick = { viewModel.save() }) {
                                Icon(
                                    Icons.Outlined.Save,
                                    contentDescription = "保存",
                                    tint = if (viewModel.hasUnsavedChanges) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                        IconButton(onClick = {
                            searchOpen = !searchOpen
                            if (!searchOpen) { query = ""; replacement = "" }
                        }) {
                            Icon(Icons.Outlined.Search, contentDescription = "搜索")
                        }
                        IconButton(onClick = { showOutline = true }) {
                            Icon(
                                Icons.AutoMirrored.Outlined.Toc,
                                contentDescription = "大纲",
                            )
                        }
                    }
                    if (usable) {
                        IconButton(onClick = { viewModel.togglePreview() }) {
                            Icon(
                                imageVector = if (viewModel.isPreview) Icons.Outlined.Edit
                                else Icons.Outlined.Visibility,
                                contentDescription = if (viewModel.isPreview) "编辑" else "预览",
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
                    .fillMaxWidth(),
            ) {
                // 文档含相对路径图片但尚未授权图片文件夹时，显示一次性引导条
                if (hasRelativeImage(viewModel.content.text) && imageTree == null) {
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
                                "文档中的图片需要授权所在文件夹才能显示",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp),
                            )
                            TextButton(onClick = { treeLauncher.launch(null) }) {
                                Text("去授权")
                            }
                        }
                    }
                }
                MarkdownPreview(
                    markdown = viewModel.content.text,
                    textSizeSp = settings.previewFontSp,
                    imageTree = imageTree,
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
                        message = if (viewModel.saveFailed) {
                            "保存失败：没有写入这个文件的权限"
                        } else {
                            "只读打开：没有写入权限，修改不会被保存"
                        },
                        actionLabel = "重新授权",
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
                        matchIndex = matchIndex
                            .coerceAtMost((matches.size - 1).coerceAtLeast(0)),
                        onPrev = { jumpToMatch(matchIndex - 1) },
                        onNext = { jumpToMatch(matchIndex + 1) },
                        onReplace = {
                            if (matches.isNotEmpty()) {
                                val start = matches[matchIndex]
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
                    placeholder = { Text("开始用 Markdown 写作…") },
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

        // 宽屏：大纲以右侧面板呈现，与左侧文件列表栏风格一致
        if (isExpanded && showOutline && !viewModel.isPreview) {
            VerticalDivider()
            OutlinePanel(
                outline = outline,
                onJump = { offset ->
                    showOutline = false
                    viewModel.jumpTo(offset)
                },
                onClose = { showOutline = false },
                modifier = Modifier
                    .width(320.dp)
                    .fillMaxHeight(),
            )
        }
        }
    }

    // 窄屏：大纲从底部弹出
    if (!isExpanded && showOutline) {
        OutlineSheet(
            outline = outline,
            onJump = { offset ->
                showOutline = false
                viewModel.jumpTo(offset)
            },
            onDismiss = { showOutline = false },
        )
    }
}

/** 顶部提示条：只读打开 / 保存失败时告知改动没有落盘，并给出重新授权入口 */
@Composable
private fun DocumentNotice(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
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
                Icons.Outlined.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.width(18.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** 读取失败时的整页提示：权限已失效，或文件被移动/删除 */
@Composable
private fun DocumentUnavailable(
    onRegrant: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(12.dp))
            Text("无法打开该文件", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "MarkNote 已经没有这个文件的访问权限，或者文件已被移动、删除。\n" +
                    "从其他应用（文件管理器「打开方式」、聊天记录等）打开的文件，" +
                    "系统通常不会给出长期权限，退出应用后就会失效。\n" +
                    "用「重新授权」在系统文件选择器里重新选一次同一个文件，之后就能一直编辑。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRegrant) { Text("重新授权") }
                TextButton(onClick = onRetry) { Text("重试") }
                TextButton(onClick = onBack) { Text("返回") }
            }
        }
    }
}

@Composable
private fun SearchPanel(
    query: String,
    onQueryChange: (String) -> Unit,
    replacement: String,
    onReplacementChange: (String) -> Unit,
    matchCount: Int,
    matchIndex: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
) {
    Surface {
        Column {
            HorizontalDivider()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp),
            ) {
                TextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text("搜索") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                Text(
                    text = if (query.isEmpty()) "" else "${if (matchCount == 0) 0 else matchIndex + 1}/$matchCount",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = onPrev, enabled = matchCount > 0) {
                    Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "上一个")
                }
                IconButton(onClick = onNext, enabled = matchCount > 0) {
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "下一个")
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭搜索")
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp),
            ) {
                TextField(
                    value = replacement,
                    onValueChange = onReplacementChange,
                    placeholder = { Text("替换为") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                TextButton(onClick = onReplace, enabled = matchCount > 0) {
                    Text("替换")
                }
                TextButton(onClick = onReplaceAll, enabled = matchCount > 0) {
                    Text("全部")
                }
            }
            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutlineSheet(
    outline: List<Heading>,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = "大纲",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        OutlineList(outline = outline, onJump = onJump)
    }
}

/** 宽屏右侧大纲面板：与左侧文件列表栏一致的侧栏风格（标题栏 + 分隔线 + 列表） */
@Composable
private fun OutlinePanel(
    outline: List<Heading>,
    onJump: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 4.dp),
            ) {
                Text(
                    text = "大纲",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭大纲")
                }
            }
            HorizontalDivider()
            OutlineList(outline = outline, onJump = onJump)
        }
    }
}

@Composable
private fun OutlineList(
    outline: List<Heading>,
    onJump: (Int) -> Unit,
) {
    if (outline.isEmpty()) {
        Text(
            text = "还没有标题，用 # 开头写一行即可生成大纲",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
        )
    } else {
        LazyColumn(
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            items(outline) { heading ->
                Text(
                    text = heading.title,
                    style = if (heading.level <= 2) MaterialTheme.typography.titleSmall
                    else MaterialTheme.typography.bodyMedium,
                    fontWeight = if (heading.level <= 2) FontWeight.Bold else FontWeight.Normal,
                    color = if (heading.level <= 2) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onJump(heading.offset) }
                        .padding(
                            start = (16 + (heading.level - 1) * 20).dp,
                            end = 24.dp,
                            top = 12.dp,
                            bottom = 12.dp,
                        ),
                )
            }
        }
    }
}

@Composable
private fun MarkdownToolbar(
    onAction: (MarkdownAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth()) {
        Column {
            HorizontalDivider()
            LazyRow(
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(markdownActions) { action ->
                    IconButton(onClick = { onAction(action) }) {
                        if (action.icon != null) {
                            Icon(
                                action.icon,
                                contentDescription = action.label,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                text = action.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
