package com.marknote.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.marknote.app.ui.editor.EditorScreen
import com.marknote.app.ui.files.FileListScreen
import com.marknote.app.ui.settings.SettingsScreen
import com.marknote.app.ui.theme.MarkNoteTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var openRequest by mutableStateOf<OpenRequest?>(null)

    override fun attachBaseContext(newBase: Context) {
        val themed = themedContext(newBase, AppThemeStore.forcedDark(newBase))
        super.attachBaseContext(localizedContext(themed, AppLocaleStore.refresh(newBase)))
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as MarkNoteApplication
        val repository = app.documentRepository
        val settings = app.settingsRepository

        if (savedInstanceState == null) handleOpenIntent(intent)
        setContent {
            val widthSizeClass = calculateWindowSizeClass(this).widthSizeClass
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            LaunchedEffect(darkTheme) {
                val barStyle = if (darkTheme) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
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
        const val EXTRA_OPEN_IN_NEW_WINDOW = "com.marknote.app.extra.OPEN_IN_NEW_WINDOW"
    }
}

private object OpenDocumentRegistry {

    private val windowCounts = mutableMapOf<String, Int>()
    private val identities = mutableMapOf<String, String>()

    @Synchronized
    fun add(uri: String) {
        windowCounts[uri] = (windowCounts[uri] ?: 0) + 1
    }

    @Synchronized
    fun remove(uri: String) {
        val left = (windowCounts[uri] ?: 0) - 1
        if (left > 0) {
            windowCounts[uri] = left
        } else {
            windowCounts.remove(uri)
            identities.remove(uri)
        }
    }

    @Synchronized
    fun setIdentity(uri: String, identity: String?) {
        if (identity != null && windowCounts.containsKey(uri)) identities[uri] = identity
    }

    @Synchronized
    fun isShown(uri: String, identity: String?): Boolean {
        if ((windowCounts[uri] ?: 0) > 0) return true
        return identity != null && identities.values.any { it == identity }
    }
}

private data class OpenRequest(val uri: Uri, val inNewWindow: Boolean)

sealed interface AppNavigationTarget {
    data object CloseEditor : AppNavigationTarget
    data object OpenSettings : AppNavigationTarget
    data class OpenDocument(val uri: String) : AppNavigationTarget
}

@Composable
private fun MarkNoteApp(
    repository: DocumentRepository,
    settings: SettingsRepository,
    isExpanded: Boolean,
    openRequest: OpenRequest?,
    onOpenRequestConsumed: () -> Unit,
) {
    var currentDoc by rememberSaveable { mutableStateOf<String?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var pendingNavigation by remember { mutableStateOf<AppNavigationTarget?>(null) }

    val applyNavigation: (AppNavigationTarget) -> Unit = { target ->
        pendingNavigation = null
        when (target) {
            AppNavigationTarget.CloseEditor -> currentDoc = null
            AppNavigationTarget.OpenSettings -> showSettings = true
            is AppNavigationTarget.OpenDocument -> {
                currentDoc = target.uri
                showSettings = false
            }
        }
    }

    val requestNavigation: (AppNavigationTarget) -> Unit = { target ->
        val editorActive = currentDoc != null && !showSettings
        val sameDocument = target is AppNavigationTarget.OpenDocument && target.uri == currentDoc
        if (!editorActive || sameDocument) {
            applyNavigation(target)
        } else {
            pendingNavigation = target
        }
    }

    var currentDocName by remember { mutableStateOf("") }

    val onDocumentRenamed: (String, String, String) -> Unit = { oldUri, newUri, newName ->
        if (currentDoc == oldUri) {
            currentDocName = newName
            if (newUri != oldUri) currentDoc = newUri
        }
    }

    val onDocumentDeleted: (String) -> Unit = { uri ->
        if (currentDoc == uri) {
            pendingNavigation = null
            currentDoc = null
        }
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val openInNewWindow: (String) -> Unit = { uri ->
        scope.launch {
            val identity = repository.fileIdentity(Uri.parse(uri))
            if (OpenDocumentRegistry.isShown(uri, identity)) {
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

    DisposableEffect(currentDoc) {
        val shown = currentDoc
        if (shown != null) OpenDocumentRegistry.add(shown)
        onDispose { if (shown != null) OpenDocumentRegistry.remove(shown) }
    }

    var pendingRegrant by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(openRequest) {
        val request = openRequest ?: return@LaunchedEffect
        if (!request.inNewWindow) {
            val persisted = withContext(Dispatchers.IO) {
                val granted = repository.persistPermission(request.uri)
                repository.addToRecents(request.uri)
                granted
            }
            if (!persisted && settings.showNoAccessNotice) pendingRegrant = request.uri.toString()
        }
        requestNavigation(AppNavigationTarget.OpenDocument(request.uri.toString()))
        onOpenRequestConsumed()
    }

    LaunchedEffect(currentDoc) {
        val doc = currentDoc
        currentDocName = doc?.let {
            withContext(Dispatchers.IO) { repository.displayName(Uri.parse(it)) }
        }.orEmpty()
        if (doc != null) OpenDocumentRegistry.setIdentity(doc, repository.fileIdentity(Uri.parse(doc)))
    }

    val fileListContent: @Composable ((() -> Unit)?) -> Unit = { onCollapse ->
        FileListScreen(
            repository = repository,
            onOpenDocument = { uri -> requestNavigation(AppNavigationTarget.OpenDocument(uri)) },
            onOpenInNewWindow = openInNewWindow,
            onOpenSettings = { requestNavigation(AppNavigationTarget.OpenSettings) },
            onDocumentRenamed = onDocumentRenamed,
            onDocumentDeleted = onDocumentDeleted,
            onCollapse = onCollapse,
            showHiddenFiles = settings.folderShowHiddenFiles,
        )
    }

    val editorContent: @Composable (String, Boolean) -> Unit = { doc, expanded ->
        EditorScreen(
            repository = repository,
            settings = settings,
            uriString = doc,
            displayName = currentDocName,
            isExpanded = expanded,
            pendingNavigation = pendingNavigation,
            onNavigationConfirmed = applyNavigation,
            onNavigationCancelled = { pendingNavigation = null },
            onRelocated = { relocated ->
                applyNavigation(AppNavigationTarget.OpenDocument(relocated))
            },
        )
    }

    if (isExpanded) {
        var sidebarVisible by rememberSaveable { mutableStateOf(true) }
        Row(Modifier.fillMaxSize()) {
            if (sidebarVisible) {
                Box(Modifier.width(360.dp)) {
                    fileListContent { sidebarVisible = false }
                }
                VerticalDivider()
            } else {
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
                    else -> editorContent(doc, true)
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
            doc != null -> editorContent(doc, false)
            else -> fileListContent(null)
        }
    }

    val regrantTarget = pendingRegrant
    if (regrantTarget != null) {
        NoPersistentAccessDialog(
            targetKey = regrantTarget,
            onDismiss = { pendingRegrant = null },
            onConfirm = { dontAskAgain ->
                if (dontAskAgain) settings.updateShowNoAccessNotice(false)
                pendingRegrant = null
            },
        )
    }
}

@Composable
private fun NoPersistentAccessDialog(
    targetKey: String,
    onDismiss: () -> Unit,
    onConfirm: (dontAskAgain: Boolean) -> Unit,
) {
    var dontAskAgain by remember(targetKey) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.no_persistent_access_title)) },
        text = {
            Column {
                Text(text = stringResource(R.string.no_persistent_access_message))
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable { dontAskAgain = !dontAskAgain },
                ) {
                    Checkbox(
                        checked = dontAskAgain,
                        onCheckedChange = { dontAskAgain = it },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.dont_show_again),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(dontAskAgain) }) {
                Text(stringResource(R.string.got_it))
            }
        },
    )
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
