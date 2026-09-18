package com.marknote.app

import android.content.Context
import android.content.Intent
import android.widget.Toast
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.marknote.app.data.AppLocaleStore
import com.marknote.app.data.AppThemeStore
import com.marknote.app.data.DocumentRepository
import com.marknote.app.data.SettingsRepository
import com.marknote.app.data.ThemeMode
import com.marknote.app.data.localizedContext
import com.marknote.app.data.themedContext
import com.marknote.app.ui.common.OpenDocumentWithInitialUri
import com.marknote.app.ui.editor.EditorScreen
import com.marknote.app.ui.files.FileListScreen
import com.marknote.app.ui.settings.SettingsScreen
import com.marknote.app.ui.theme.MarkNoteTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    /** 待处理的打开请求（外部 VIEW/EDIT，或应用内「在新窗口打开」），桥接给 Compose */
    private var openRequest by mutableStateOf<OpenRequest?>(null)

    /**
     * 套用应用内语言与深浅色。必须在 attachBaseContext 阶段完成：此时 Activity 的 Resources
     * 还没被使用，包一层之后 Compose 的 stringResource、Material 组件的默认文案、
     * **以及系统栏图标与窗口背景**（后者走的是 `values-night/` 这种 night 限定符）才都是目标设定。
     * 两个设定都会改 Configuration，各包一层 —— 后一层是基于前一层复制的配置，不会互相覆盖。
     * 切换语言或主题时会重建 Activity，本方法随之重新执行。
     *
     * 语言用 refresh 而不是读缓存：Android 13+ 用户可能在系统「应用语言」里改过，
     * 系统改完会重建 Activity，而 attachBaseContext 正是唯一「早于 Resources 被使用」的
     * 时机，在这里重新解析才能让新语言立刻生效。
     */
    override fun attachBaseContext(newBase: Context) {
        val themed = themedContext(newBase, AppThemeStore.forcedDark(newBase))
        super.attachBaseContext(localizedContext(themed, AppLocaleStore.refresh(newBase)))
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = DocumentRepository(applicationContext)
        val settings = SettingsRepository(applicationContext)
        // 只在真正的首次启动时处理 intent。旋转、进出分屏、改窗口尺寸都会重建 Activity，
        // 重建时若重放一次打开动作，就会**再申请一次持久授权** —— 拿不到长期授权的外部文件
        // 会在每次重建后再弹一次「无法长期访问」提示（多窗口下来回分屏时尤其烦）。
        // 重建时 currentDoc 由 rememberSaveable 复原，本来也不需要重放。
        if (savedInstanceState == null) handleOpenIntent(intent)
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
                    openRequest = openRequest,
                    onOpenRequestConsumed = { openRequest = null },
                )
            }
        }
    }

    /**
     * 应用已在运行时再次打开文件会走这里。
     *
     * 注意本应用是**多实例**的（manifest 的 `documentLaunchMode="intoExisting"`）：同一份文档
     * 已经开着时会复用它的窗口（本方法随之触发），不同文档则各开一个新窗口、各走各的 onCreate。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpenIntent(intent)
    }

    private fun handleOpenIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        val action = intent.action ?: return
        if (action != Intent.ACTION_VIEW && action != Intent.ACTION_EDIT) return
        openRequest = OpenRequest(
            uri = uri,
            inNewWindow = intent.getBooleanExtra(EXTRA_OPEN_IN_NEW_WINDOW, false),
        )
    }

    companion object {
        /**
         * 应用内「在新窗口打开」时带上：这种打开方式不必再申请持久授权、也不必重复登记最近列表
         * （发起方是应用内的一栏列表，两件事都已经做过）。
         */
        const val EXTRA_OPEN_IN_NEW_WINDOW = "com.marknote.app.extra.OPEN_IN_NEW_WINDOW"
    }
}

/**
 * 当前**进程内**每份文档正被哪些窗口显示着（多实例的各个窗口都在同一个进程里，一张内存登记表
 * 就够，不需要跨进程）。
 *
 * 用途只有一个：「在新窗口打开」之前查一下 —— 已经开着的不再开第二个编辑器。两份编辑器同时自动
 * 保存同一个文件，谁后写谁覆盖，用户完全看不出来。
 *
 * **按「文件身份」而不只是按 Uri 判断**：同一份文件从不同来源拿到的是两个 Uri（见
 * [DocumentRepository.fileIdentity]），只比 Uri 会漏掉这种情况。身份是异步算出来的（要开一次
 * 文件描述符），所以它单独存、后补；还没算出来时退回按 Uri 判断 —— 那两个 Uri 至少是「自己等于
 * 自己」，不会误放。
 */
internal object OpenDocumentRegistry {
    /** uri → 显示它的窗口数（计数而非布尔：本来就可能已经开着两份） */
    private val windowCounts = mutableMapOf<String, Int>()

    /** uri → 文件身份（算出来才登记） */
    private val identities = mutableMapOf<String, String>()

    fun add(uri: String) {
        windowCounts[uri] = (windowCounts[uri] ?: 0) + 1
    }

    fun remove(uri: String) {
        val left = (windowCounts[uri] ?: 0) - 1
        if (left > 0) {
            windowCounts[uri] = left
        } else {
            windowCounts.remove(uri)
            identities.remove(uri)
        }
    }

    /** 补登记身份（异步算出来之后调用；该文档已经不在任何窗口显示时忽略） */
    fun setIdentity(uri: String, identity: String?) {
        if (identity != null && windowCounts.containsKey(uri)) identities[uri] = identity
    }

    /** 有没有哪块窗口正在显示这份文档（同 Uri，或身份相同） */
    fun isShown(uri: String, identity: String?): Boolean {
        if ((windowCounts[uri] ?: 0) > 0) return true
        return identity != null && identities.values.any { it == identity }
    }
}

/**
 * 待处理的一次打开请求。
 * [inNewWindow] 为 true 表示来自应用内列表的「在新窗口打开」。
 */
internal data class OpenRequest(val uri: Uri, val inNewWindow: Boolean)

/**
 * 导航与布局：
 * - 窄屏（手机/竖屏）：单栏，列表 ↔ 编辑器 跳转
 * - 宽屏（≥840dp，平板/横屏）：左栏最近文件列表 + 右栏编辑器 双栏同屏
 * currentDoc 为 null 时在编辑器位置显示空状态。
 *
 * **多窗口**：本应用是多实例的（manifest 的 `documentLaunchMode="intoExisting"`），
 * 同一个应用可以在分屏里占两块、各显示一份文档。每个窗口是一个独立 Activity，
 * 因此窗口之间的编辑状态天然隔离 —— 这里不需要任何额外机制。
 */
@Composable
internal fun MarkNoteApp(
    repository: DocumentRepository,
    settings: SettingsRepository,
    isExpanded: Boolean,
    openRequest: OpenRequest?,
    onOpenRequestConsumed: () -> Unit,
) {
    var currentDoc by rememberSaveable { mutableStateOf<String?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    // 从编辑器返回时自增，通知列表刷新（摘要可能已被编辑改变）。
    // 必须 rememberSaveable：配置变更（旋转、进出分屏、切语言/主题的 recreate）会重建组合，
    // 归零之后「该刷新了」这个信号就丢了 —— 而宽屏侧栏可能正是那次重建才第一次组合出来的，
    // 它会拿着一份过期列表一直挂在那儿（2026-09-18 实测撞到）。
    var listRefreshTick by rememberSaveable { mutableStateOf(0) }

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

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    /**
     * 在新窗口打开：起一个新的 Activity 实例（多实例），这份文档在那边显示。
     *
     * 不加 `FLAG_ACTIVITY_MULTIPLE_TASK` —— 加了它，同一份文档必定开出第二个窗口，两份副本各自
     * 自动保存，谁后写谁覆盖，用户看不出发生了什么。
     *
     * 但**光靠平台去重不够**：`documentLaunchMode="intoExisting"` 只在「这份文档自己有过一个窗口
     * （即以它自己的 VIEW intent 开出来的 task）」时才把旧窗口带到前台。用列表点开的文档没有这样
     * 的窗口，于是「在新窗口打开」会真的多开一个编辑器。所以这里再查一次进程内的登记表
     * （见 [OpenDocumentRegistry]）：只要还有窗口在显示这份文档，就不开第二个。
     */
    val openInNewWindow: (String) -> Unit = { uri ->
        // 文件身份要开一次文件描述符（跨进程 IO），所以先算身份再决定
        scope.launch {
            val identity = repository.fileIdentity(Uri.parse(uri))
            if (OpenDocumentRegistry.isShown(uri, identity)) {
                // 各屏自带 Scaffold、没有 Snackbar 宿主，用 Toast 说明一句即可，不抢焦点
                Toast.makeText(context, R.string.already_open_in_a_window, Toast.LENGTH_SHORT).show()
            } else {
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .setAction(Intent.ACTION_VIEW)
                        .setData(Uri.parse(uri))
                        .putExtra(MainActivity.EXTRA_OPEN_IN_NEW_WINDOW, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT),
                )
            }
        }
    }

    // 登记「本窗口正在显示哪份文档」，供上面的检查与 [OpenDocumentRegistry] 使用。
    // DisposableEffect(currentDoc)：换文档时先注销旧的、再登记新的；窗口销毁时自动注销。
    DisposableEffect(currentDoc) {
        val shown = currentDoc
        if (shown != null) OpenDocumentRegistry.add(shown)
        onDispose { if (shown != null) OpenDocumentRegistry.remove(shown) }
    }

    // 外部 Uri 拿不到长期权限时待处理的重新授权目标（弹窗引导用户重选一次）
    var pendingRegrant by remember { mutableStateOf<String?>(null) }

    // 打开一份文档。外部来源（文件管理器、聊天记录等）要申请持久化授权并记入最近列表；
    // 应用内「在新窗口打开」这两步都已经做过，直接显示即可，也不该再弹授权提示。
    LaunchedEffect(openRequest) {
        val request = openRequest ?: return@LaunchedEffect
        if (!request.inNewWindow) {
            // 这两步都要跨进程问 provider（addToRecents 内部会查 ContentProvider 取显示名），
            // 放在 IO 线程做，避免主线程被 provider 的响应时间拖住
            val persisted = withContext(Dispatchers.IO) {
                val granted = repository.persistPermission(request.uri)
                repository.addToRecents(request.uri)
                granted
            }
            // 拿不到长期权限（文件管理器「打开方式」、聊天记录分享等来源常见）：
            // 授权只在本进程内有效，退出应用后就打不开了，立刻提示用户重新授权
            if (!persisted) pendingRegrant = request.uri.toString()
            // 刚往最近列表里加了一条，得让列表重读一次 —— 宽屏的侧栏一直在组合里，
            // 没有这个信号它会一直挂着打开之前那份列表（2026-09-18 在平板上实测到：
            // 编辑器里已经打开 tA，侧栏还写着「还没有打开过文件」）。
            listRefreshTick++
        }
        currentDoc = request.uri.toString()
        showSettings = false
        onOpenRequestConsumed()
    }

    // 重新授权：用系统文档选择器重选同一个文件，换来可持久化的授权
    val regrantTarget = pendingRegrant
    val regrantLauncher = rememberLauncherForActivityResult(
        remember(regrantTarget) { OpenDocumentWithInitialUri(regrantTarget?.let(Uri::parse)) },
    ) { picked ->
        val old = regrantTarget
        if (picked != null && old != null) {
            // 与上面外部打开那条路同一个理由：persistPermission 要跨进程问 provider，
            // replaceRecent 内部还要查一次显示名（ContentProvider 查询）。都放 IO 做，
            // 别让回调所在的主线程被 provider 的响应时间拖住。
            scope.launch {
                withContext(Dispatchers.IO) {
                    repository.persistPermission(picked)
                    repository.replaceRecent(old, picked)
                }
                openDocument(picked.toString())
            }
        }
        pendingRegrant = null
    }

    // 文件名要问 ContentProvider（跨进程 IPC）。放在组合里同步查会拖住主线程（切文档时
    // 尤其明显），所以改为挂到 currentDoc 上异步取；取到之前沿用上一个名字，避免标题闪空。
    var currentDocName by remember { mutableStateOf("") }
    LaunchedEffect(currentDoc) {
        val doc = currentDoc
        currentDocName = doc?.let {
            withContext(Dispatchers.IO) { repository.displayName(Uri.parse(it)) }
        }.orEmpty()
        // 文件身份单独补登记（要开一次 fd，所以异步）：同一份文件的不同来源 Uri 靠它对齐，
        // 算不出来时登记表退回按 Uri 判断。
        if (doc != null) OpenDocumentRegistry.setIdentity(doc, repository.fileIdentity(Uri.parse(doc)))
    }

    if (isExpanded) {
        var sidebarVisible by rememberSaveable { mutableStateOf(true) }
        Row(Modifier.fillMaxSize()) {
            if (sidebarVisible) {
                Box(Modifier.width(360.dp)) {
                    FileListScreen(
                        repository = repository,
                        onOpenDocument = openDocument,
                        onOpenInNewWindow = openInNewWindow,
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
                    else -> EditorArea(
                        doc = doc,
                        repository = repository,
                        settings = settings,
                        displayName = currentDocName,
                        isExpanded = true,
                        onBack = closeEditor,
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
            doc != null -> EditorArea(
                doc = doc,
                repository = repository,
                settings = settings,
                displayName = currentDocName,
                isExpanded = false,
                onBack = closeEditor,
                onRelocated = openDocument,
            )
            else -> FileListScreen(
                repository = repository,
                onOpenDocument = openDocument,
                onOpenInNewWindow = openInNewWindow,
                onOpenSettings = { showSettings = true },
                refreshTick = listRefreshTick,
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

/**
 * 两套布局（窄屏单栏 / 宽屏双栏）里的编辑器是同一个东西，参数只写一份。
 *
 * `key(doc)`：换文档时整棵编辑器重建。搜索框、大纲面板这些**不挂在 ViewModel 上**的
 * 界面状态必须跟着换 —— 否则切过去还留着上一份文档的搜索词与命中序号（它们用的是不带
 * uri 作 key 的 `remember`）。代价是换文档会回到顶部，这是有意的取舍：宁可回到顶部，
 * 也不能让两份文档的界面状态串在一起。
 */
@Composable
private fun EditorArea(
    doc: String,
    repository: DocumentRepository,
    settings: SettingsRepository,
    displayName: String,
    isExpanded: Boolean,
    onBack: () -> Unit,
    onRelocated: (String) -> Unit,
) {
    key(doc) {
        EditorScreen(
            repository = repository,
            settings = settings,
            uriString = doc,
            displayName = displayName,
            onBack = onBack,
            isExpanded = isExpanded,
            onRelocated = onRelocated,
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
