package com.marknote.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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

    // 外部打开：持久化权限、记入最近列表、直接进编辑器
    LaunchedEffect(externalUri) {
        val uri = externalUri ?: return@LaunchedEffect
        repository.persistPermission(uri)
        repository.addToRecents(uri)
        currentDoc = uri.toString()
        showSettings = false
        onExternalUriConsumed()
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
                        onOpenDocument = { uri ->
                            currentDoc = uri
                            showSettings = false
                        },
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
                onOpenDocument = { uri ->
                    currentDoc = uri
                    showSettings = false
                },
                onOpenSettings = { showSettings = true },
                refreshTick = listRefreshTick,
            )
            else -> EditorScreen(
                repository = repository,
                settings = settings,
                uriString = doc,
                displayName = currentDocName,
                onBack = closeEditor,
            )
        }
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
