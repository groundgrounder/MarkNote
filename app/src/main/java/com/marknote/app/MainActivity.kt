package com.marknote.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.marknote.app.data.DocumentRepository
import com.marknote.app.data.SettingsRepository
import com.marknote.app.data.ThemeMode
import com.marknote.app.ui.common.OpenDocumentWithInitialUri
import com.marknote.app.ui.editor.EditorScreen
import com.marknote.app.ui.files.FileListScreen
import com.marknote.app.ui.settings.SettingsScreen
import com.marknote.app.ui.theme.MarkNoteTheme

class MainActivity : ComponentActivity() {

    /** 外部（文件管理器等）通过 VIEW/EDIT intent 传入的文档 Uri，桥接给 Compose */
    private var externalUri by mutableStateOf<Uri?>(null)

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = DocumentRepository(applicationContext)
        val settings = SettingsRepository(applicationContext)
        handleOpenIntent(intent)
        setContent {
            val widthSizeClass = calculateWindowSizeClass(this).widthSizeClass
            // 主题模式：跟随系统 / 强制浅色 / 强制深色
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            MarkNoteTheme(darkTheme = darkTheme) {
                MarkNoteApp(
                    repository = repository,
                    settings = settings,
                    isExpanded = widthSizeClass == WindowWidthSizeClass.Expanded,
                    externalUri = externalUri,
                    onExternalUriConsumed = { externalUri = null },
                )
            }
        }
    }

    /** singleTask 模式下，应用已在运行时外部再次打开文件会走这里 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpenIntent(intent)
    }

    private fun handleOpenIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        when (intent.action) {
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> externalUri = uri
        }
    }
}

/**
 * 导航与布局：
 * - 窄屏（手机/竖屏）：单栏，列表 ↔ 编辑器 跳转
 * - 宽屏（≥840dp，平板/横屏）：左栏最近文件列表 + 右栏编辑器 双栏同屏
 * currentDoc 为 null 时在编辑器位置显示空状态。
 */
@Composable
fun MarkNoteApp(
    repository: DocumentRepository,
    settings: SettingsRepository,
    isExpanded: Boolean,
    externalUri: Uri?,
    onExternalUriConsumed: () -> Unit,
) {
    var currentDoc by rememberSaveable { mutableStateOf<String?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    // 从编辑器返回时自增，通知列表刷新（摘要可能已被编辑改变）
    var listRefreshTick by remember { mutableIntStateOf(0) }
    val closeEditor: () -> Unit = {
        currentDoc = null
        listRefreshTick++
    }

    /** 重新授权/换文件后切换当前文档（最近列表里旧条目已被替换，需要刷新） */
    val openDocument: (String) -> Unit = { uri ->
        currentDoc = uri
        showSettings = false
        listRefreshTick++
    }

    // 外部 Uri 拿不到长期权限时待处理的重新授权目标（弹窗引导用户重选一次）
    var pendingRegrant by remember { mutableStateOf<String?>(null) }

    // 外部打开：持久化权限、记入最近列表、直接进编辑器
    LaunchedEffect(externalUri) {
        val uri = externalUri ?: return@LaunchedEffect
        val persisted = repository.persistPermission(uri)
        repository.addToRecents(uri)
        currentDoc = uri.toString()
        showSettings = false
        // 拿不到长期权限（文件管理器「打开方式」、聊天记录分享等来源常见）：
        // 授权只在本进程内有效，退出应用后就打不开了，立刻提示用户重新授权
        if (!persisted) pendingRegrant = uri.toString()
        onExternalUriConsumed()
    }

    // 重新授权：用系统文档选择器重选同一个文件，换来可持久化的授权
    val regrantTarget = pendingRegrant
    val regrantLauncher = rememberLauncherForActivityResult(
        remember(regrantTarget) { OpenDocumentWithInitialUri(regrantTarget?.let(Uri::parse)) },
    ) { picked ->
        val old = regrantTarget
        if (picked != null && old != null) {
            repository.persistPermission(picked)
            repository.replaceRecent(old, picked)
            openDocument(picked.toString())
        }
        pendingRegrant = null
    }

    // 文件名查询走 ContentProvider（主线程 IPC），缓存避免每次重组都查
    val currentDocName = remember(currentDoc) {
        currentDoc?.let { repository.displayName(Uri.parse(it)) } ?: ""
    }

    if (isExpanded) {
        var sidebarVisible by rememberSaveable { mutableStateOf(true) }
        Row(Modifier.fillMaxSize()) {
            if (sidebarVisible) {
                Box(Modifier.width(360.dp)) {
                    FileListScreen(
                        repository = repository,
                        onOpenDocument = openDocument,
                        onOpenSettings = { showSettings = true },
                        onCollapse = { sidebarVisible = false },
                        refreshTick = listRefreshTick,
                    )
                }
                VerticalDivider()
            } else {
                // 收起后的窄条：保留一个展开入口（避让状态栏）
                Surface(Modifier.width(56.dp).fillMaxHeight()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.statusBarsPadding(),
                    ) {
                        IconButton(
                            onClick = { sidebarVisible = true },
                            modifier = Modifier.padding(top = 12.dp),
                        ) {
                            Icon(Icons.Outlined.Menu, contentDescription = "展开侧栏")
                        }
                    }
                }
                VerticalDivider()
            }
            Box(Modifier.weight(1f)) {
                val doc = currentDoc
                when {
                    showSettings -> SettingsScreen(
                        settings = settings,
                        onBack = { showSettings = false },
                    )
                    doc == null -> EmptyEditorHint()
                    else -> EditorScreen(
                        repository = repository,
                        settings = settings,
                        uriString = doc,
                        displayName = currentDocName,
                        onBack = closeEditor,
                        isExpanded = true,
                        onRelocated = openDocument,
                    )
                }
            }
        }
    } else {
        val doc = currentDoc
        when {
            showSettings -> SettingsScreen(
                settings = settings,
                onBack = { showSettings = false },
            )
            doc == null -> FileListScreen(
                repository = repository,
                onOpenDocument = openDocument,
                onOpenSettings = { showSettings = true },
                refreshTick = listRefreshTick,
            )
            else -> EditorScreen(
                repository = repository,
                settings = settings,
                uriString = doc,
                displayName = currentDocName,
                onBack = closeEditor,
                onRelocated = openDocument,
            )
        }
    }

    // 外部来源的 Uri 拿不到长期权限时，第一时间说明后果并引导重新授权，
    // 否则用户会在「退出应用后重新进入」时才发现文件打不开。
    if (regrantTarget != null) {
        AlertDialog(
            onDismissRequest = { pendingRegrant = null },
            title = { Text("这个文件无法长期访问") },
            text = {
                // 字号沿用 AlertDialog 默认（bodyMedium）：不覆盖全局排版风格
                Text(
                    text = "其他应用分享的文件没有长期权限，" +
                        "退出 MarkNote 后就打不开。重新授权即可长期编辑。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        regrantLauncher.launch(arrayOf("text/markdown", "text/plain", "*/*"))
                    },
                ) { Text("重新授权") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRegrant = null }) { Text("暂时编辑") }
            },
        )
    }
}

@Composable
private fun EmptyEditorHint() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "从左侧打开最近文件，或「打开文件」选择文档",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
