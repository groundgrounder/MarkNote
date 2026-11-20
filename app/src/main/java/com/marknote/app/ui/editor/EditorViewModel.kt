package com.marknote.app.ui.editor

import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.marknote.app.data.DocumentEncoding
import com.marknote.app.data.DocumentEvent
import com.marknote.app.data.DocumentReadResult
import com.marknote.app.data.DocumentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface DocumentLoadState {
    data object Loading : DocumentLoadState
    data object Loaded : DocumentLoadState
    data class TooLarge(val limitMb: Int) : DocumentLoadState
    data object Unavailable : DocumentLoadState
}

class EditorViewModel(
    private val repository: DocumentRepository,
) : ViewModel() {

    var uriString by mutableStateOf("")
        private set

    private var uri: Uri = Uri.EMPTY
    private var loadJob: Job? = null

    var loadState by mutableStateOf<DocumentLoadState>(DocumentLoadState.Loading)
        private set

    val isLoaded: Boolean
        get() = loadState is DocumentLoadState.Loaded

    var content by mutableStateOf(TextFieldValue(""))
        private set

    var isPreview by mutableStateOf(false)
        private set

    var readOnly by mutableStateOf(false)
        private set

    var saveFailed by mutableStateOf(false)
        private set

    var hasPersistedAccess by mutableStateOf(true)
        private set

    var imageTree by mutableStateOf<String?>(null)
        private set

    var imageTreeResolved by mutableStateOf(false)
        private set

    private var lastSavedText = ""
    private var encoding = DocumentEncoding.UTF8
    private val saveMutex = Mutex()
    private var lastSyncAtMs = 0L

    private val undoStack = UndoStack()

    var canUndo by mutableStateOf(false)
        private set

    var canRedo by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            repository.documentEvents.collect { event ->
                when (event) {
                    is DocumentEvent.Renamed -> {
                        if (uriString == event.oldUri && event.newUri != event.oldUri) {
                            uriString = event.newUri
                            uri = Uri.parse(event.newUri)
                        }
                    }
                    is DocumentEvent.Deleted -> {
                        if (uriString == event.uri) reset()
                    }
                }
            }
        }
    }

    private fun refreshUndoState() {
        canUndo = undoStack.canUndo
        canRedo = undoStack.canRedo
    }

    private fun recordEdit(oldText: String, newText: String, coalesce: Boolean = true) {
        undoStack.record(oldText, newText, SystemClock.elapsedRealtime(), coalesce)
        refreshUndoState()
    }

    private fun clearUndoHistory() {
        undoStack.clear()
        refreshUndoState()
    }

    fun open(targetUriString: String) {
        if (uriString == targetUriString && loadState !is DocumentLoadState.Unavailable) {
            syncFromDiskIfClean()
            return
        }
        loadJob?.cancel()
        uriString = targetUriString
        uri = Uri.parse(targetUriString)
        content = TextFieldValue("")
        lastSavedText = ""
        encoding = DocumentEncoding.UTF8
        isPreview = false
        readOnly = false
        saveFailed = false
        hasPersistedAccess = true
        imageTree = null
        imageTreeResolved = false
        lastSyncAtMs = 0L
        clearUndoHistory()
        load()
    }

    fun load() {
        val targetUri = uri
        val targetUriString = uriString
        if (targetUri == Uri.EMPTY) return
        loadJob?.cancel()
        loadState = DocumentLoadState.Loading
        loadJob = viewModelScope.launch {
            val resolvedTree = withContext(Dispatchers.IO) {
                repository.imageTreeFor(targetUriString)
                    ?.takeIf { repository.hasPersistedPermission(Uri.parse(it)) }
            }
            if (uri == targetUri) {
                imageTree = resolvedTree
                imageTreeResolved = true
            }
            when (val result = repository.readDocumentResult(targetUri)) {
                is DocumentReadResult.TooLarge -> {
                    loadState = DocumentLoadState.TooLarge(result.limitMb)
                }
                DocumentReadResult.Unavailable -> {
                    loadState = DocumentLoadState.Unavailable
                }
                is DocumentReadResult.Success -> {
                    val document = result.document
                    encoding = document.encoding
                    lastSavedText = document.text
                    content = TextFieldValue(document.text, TextRange(document.text.length))
                    clearUndoHistory()
                    saveFailed = false
                    readOnly = !repository.canWrite(targetUri)
                    hasPersistedAccess = withContext(Dispatchers.IO) {
                        repository.hasPersistedPermission(targetUri)
                    }
                    lastSyncAtMs = SystemClock.elapsedRealtime()
                    loadState = DocumentLoadState.Loaded
                }
            }
        }
    }

    fun grantImageTree(treeUri: Uri) {
        val docUri = uriString
        if (docUri.isEmpty()) return
        imageTree = treeUri.toString()
        imageTreeResolved = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.persistPermission(treeUri)
                repository.setImageTree(docUri, treeUri.toString())
            }
        }
    }

    fun regrantDocument(picked: Uri, onRelocated: (String) -> Unit) {
        val oldUri = uriString
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.persistPermission(picked)
                if (oldUri.isNotEmpty()) repository.replaceRecent(oldUri, picked)
            }
            onRelocated(picked.toString())
        }
    }

    fun syncFromDiskIfClean() {
        if (!isLoaded || hasUnsavedChanges) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSyncAtMs < SYNC_THROTTLE_MS) return
        lastSyncAtMs = now

        val targetUri = uri
        val snapshot = content.text
        viewModelScope.launch {
            val result = repository.readDocumentResult(targetUri) as? DocumentReadResult.Success
                ?: return@launch
            if (uri != targetUri || content.text != snapshot) return@launch
            val document = result.document
            if (document.text == snapshot) return@launch
            encoding = document.encoding
            lastSavedText = document.text
            val cursor = content.selection.start.coerceAtMost(document.text.length)
            content = TextFieldValue(document.text, TextRange(cursor))
            clearUndoHistory()
        }
    }

    fun onContentChange(newValue: TextFieldValue) {
        val adjusted = continueListIfJustEntered(newValue) ?: newValue
        recordEdit(content.text, adjusted.text)
        content = adjusted
    }

    private fun continueListIfJustEntered(newValue: TextFieldValue): TextFieldValue? {
        val old = content.text
        val new = newValue.text
        if (new.length != old.length + 1) return null
        val selection = newValue.selection
        if (!selection.collapsed) return null
        val at = selection.start - 1
        if (at < 0 || at >= new.length || new[at] != '\n') return null
        if (!old.regionMatches(0, new, 0, at)) return null
        if (!old.regionMatches(at, new, at + 1, old.length - at)) return null
        val edit = continueListOnNewline(new, at + 1) ?: return null
        return TextFieldValue(edit.text, TextRange(edit.selectionStart, edit.selectionEnd))
    }

    internal fun applyEdit(edit: EditResult) {
        recordEdit(content.text, edit.text, coalesce = false)
        content = TextFieldValue(edit.text, TextRange(edit.selectionStart, edit.selectionEnd))
    }

    fun indentSelection(outdent: Boolean): Boolean {
        val selection = content.selection
        val edit = indentLines(
            text = content.text,
            selectionStart = selection.min,
            selectionEnd = selection.max,
            outdent = outdent,
        ) ?: return false
        applyEdit(edit)
        return true
    }

    fun toggleTask(taskIndex: Int) {
        val edit = toggleTaskCheckbox(content.text, taskIndex) ?: return
        applyEdit(edit)
    }

    fun undo() {
        val outcome = undoStack.undo(content.text)
        refreshUndoState()
        if (outcome != null) applyOutcome(outcome)
    }

    fun redo() {
        val outcome = undoStack.redo(content.text)
        refreshUndoState()
        if (outcome != null) applyOutcome(outcome)
    }

    private fun applyOutcome(outcome: UndoOutcome) {
        val caret = outcome.caret.coerceIn(0, outcome.text.length)
        content = TextFieldValue(outcome.text, TextRange(caret))
    }

    fun jumpTo(offset: Int) {
        val safe = offset.coerceIn(0, content.text.length)
        content = content.copy(selection = TextRange(safe))
    }

    fun selectRange(start: Int, end: Int) {
        val s = start.coerceIn(0, content.text.length)
        val e = end.coerceIn(s, content.text.length)
        content = content.copy(selection = TextRange(s, e))
    }

    fun replaceInRange(start: Int, end: Int, newText: String) {
        val s = start.coerceIn(0, content.text.length)
        val e = end.coerceIn(s, content.text.length)
        val replaced = content.text.replaceRange(s, e, newText)
        recordEdit(content.text, replaced, coalesce = false)
        content = TextFieldValue(replaced, TextRange(s + newText.length))
    }

    fun replaceAll(query: String, replacement: String, ignoreCase: Boolean = false): Int {
        val text = content.text
        if (query.isEmpty()) return 0
        var count = 0
        var cursor = 0
        val out = StringBuilder(text.length)
        while (true) {
            val found = text.indexOf(query, cursor, ignoreCase = ignoreCase)
            if (found < 0) break
            out.append(text, cursor, found)
            out.append(replacement)
            cursor = found + query.length
            count++
        }
        if (count > 0) {
            out.append(text, cursor, text.length)
            val newText = out.toString()
            recordEdit(text, newText, coalesce = false)
            content = TextFieldValue(newText, TextRange(content.selection.start.coerceAtMost(newText.length)))
        }
        return count
    }

    fun stats(text: String = content.text): Pair<Int, Int> {
        if (text.isEmpty()) return 0 to 0
        var chars = 0
        var lines = 1
        text.forEach { char ->
            if (!char.isWhitespace()) chars++
            if (char == '\n') lines++
        }
        return chars to lines
    }

    fun togglePreview() {
        if (!isPreview) save()
        isPreview = !isPreview
    }

    val hasUnsavedChanges: Boolean
        get() = isLoaded && content.text != lastSavedText

    fun reset() {
        loadJob?.cancel()
        uriString = ""
        uri = Uri.EMPTY
        content = TextFieldValue("")
        lastSavedText = ""
        encoding = DocumentEncoding.UTF8
        isPreview = false
        readOnly = false
        saveFailed = false
        hasPersistedAccess = true
        imageTree = null
        imageTreeResolved = false
        lastSyncAtMs = 0L
        loadState = DocumentLoadState.Loading
        clearUndoHistory()
    }

    fun save() {
        if (!hasUnsavedChanges) return
        val targetUri = uri
        val targetText = content.text
        val targetEncoding = encoding
        viewModelScope.launch {
            saveSnapshot(targetUri, targetText, targetEncoding)
        }
    }

    suspend fun saveAndWait(): Boolean {
        if (!isLoaded) return true
        return saveSnapshot(uri, content.text, encoding)
    }

    private suspend fun saveSnapshot(
        targetUri: Uri,
        targetText: String,
        targetEncoding: DocumentEncoding,
    ): Boolean = saveMutex.withLock {
        val stillCurrent = uri == targetUri
        if (stillCurrent && targetText == lastSavedText) return@withLock true
        val ok = repository.saveDocument(targetUri, targetText, targetEncoding)
        if (uri == targetUri) {
            if (ok) {
                lastSavedText = targetText
                saveFailed = false
                readOnly = false
            } else {
                saveFailed = true
            }
        }
        ok
    }

    internal fun applyAction(action: MarkdownAction) {
        val current = content
        val sel = current.selection
        val selected = current.text.substring(sel.min, sel.max)
        val insert = action.prefix + selected.ifEmpty { action.placeholder } + action.suffix
        val newText = current.text.replaceRange(sel.min, sel.max, insert)
        val cursor = if (selected.isEmpty() && action.placeholder.isNotEmpty()) {
            sel.min + action.prefix.length + action.placeholder.length
        } else {
            sel.min + insert.length
        }
        recordEdit(current.text, newText, coalesce = false)
        content = TextFieldValue(newText, TextRange(cursor))
    }

    private companion object {
        const val SYNC_THROTTLE_MS = 2_000L
    }
}
