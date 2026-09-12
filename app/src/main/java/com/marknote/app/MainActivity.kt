package com.marknote.app

import android.content.Context
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.marknote.app.data.AppLocaleStore
import com.marknote.app.data.DocumentRepository
import com.marknote.app.data.SettingsRepository
import com.marknote.app.data.ThemeMode
import com.marknote.app.data.localizedContext
import com.marknote.app.ui.common.OpenDocumentWithInitialUri
import com.marknote.app.ui.editor.EditorScreen
import com.marknote.app.ui.files.FileListScreen
import com.marknote.app.ui.settings.SettingsScreen
import com.marknote.app.ui.theme.MarkNoteTheme

class MainActivity : ComponentActivity() {

    /** 外部（文件管理器等）通过 VIEW/EDIT intent 传入的文档 Uri，桥接给 Compose */
    private var externalUri by mutableStateOf<Uri?>(null)

    /**
     * 套用应用内语言。必须在 attachBaseContext 阶段完成：此时 Activity 的 Resources
     * 还没被使用，包一层之后 Compose 的 stringResource、Material 组件的默认文案
     * 才都是目标语言。切换语言时会重建 Activity，本方法随之重新执行。
     *
     * 这里用 refresh 而不是读缓存：Android 13+ 用户可能在系统「应用语言」里改过，
     * 系统改完会重建 Activity，而 attachBaseContext 正是唯一「早于 Resources 被使用」的
     * 时机，在这里重新解析才能让新语言立刻生效。
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(localizedContext(newBase, AppLocaleStore.refresh(newBase)))
    }

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
                            Icon(
                                Icons.Outlined.Menu,
                                contentDescription = stringResource(R.string.expand_sidebar),
                            )
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
            title = { Text(stringResource(R.string.no_persistent_access_title)) },
            text = {
                // 字号沿用 AlertDialog 默认（bodyMedium）：不覆盖全局排版风格
                Text(text = stringResource(R.string.no_persistent_access_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        regrantLauncher.launch(arrayOf("text/markdown", "text/plain", "*/*"))
                    },
                ) { Text(stringResource(R.string.regrant)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRegrant = null }) {
                    Text(stringResource(R.string.edit_temporarily))
                }
            },
        )
    }
}

@Composable
private fun EmptyEditorHint() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.empty_editor_hint),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
