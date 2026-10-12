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
import com.marknote.app.data.DocumentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EditorViewModel(
    private val repository: DocumentRepository,
    val uriString: String,
) : ViewModel() {

    private val uri: Uri = Uri.parse(uriString)

    var content by mutableStateOf(TextFieldValue(""))
        private set

    var isPreview by mutableStateOf(false)
        private set

    var isLoaded by mutableStateOf(false)
        private set

    var readOnly by mutableStateOf(false)
        private set

    var saveFailed by mutableStateOf(false)
        private set

    var hasPersistedAccess by mutableStateOf(true)
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

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val document = repository.readDocument(uri)
            if (document == null) {
                isLoaded = false
                return@launch
            }
            encoding = document.encoding
            lastSavedText = document.text
            content = TextFieldValue(document.text, TextRange(document.text.length))
            clearUndoHistory()
            isLoaded = true
            saveFailed = false
            readOnly = !repository.canWrite(uri)

            hasPersistedAccess = withContext(Dispatchers.IO) { repository.hasPersistedPermission(uri) }
            lastSyncAtMs = SystemClock.elapsedRealtime()
        }
    }

    fun syncFromDiskIfClean() {
        if (!isLoaded || hasUnsavedChanges) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastSyncAtMs < SYNC_THROTTLE_MS) return
        lastSyncAtMs = now

        val snapshot = content.text
        viewModelScope.launch {
            val document = repository.readDocument(uri) ?: return@launch

            if (content.text != snapshot) return@launch

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

    fun replaceAll(query: String, replacement: String): Int {
        val text = content.text
        if (query.isEmpty()) return 0
        var count = 0
        var i = text.indexOf(query)
        while (i >= 0) {
            count++
            i = text.indexOf(query, i + query.length)
        }
        if (count > 0) {
            val newText = text.replace(query, replacement)

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
        get() = content.text != lastSavedText

    fun save() {

        if (!isLoaded) return
        if (content.text == lastSavedText) return
        viewModelScope.launch {
            saveMutex.withLock {

                val text = content.text
                if (text == lastSavedText) return@withLock
                if (repository.saveDocument(uri, text, encoding)) {
                    lastSavedText = text
                    saveFailed = false
                    readOnly = false
                } else {

                    saveFailed = true
                }
            }
        }
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
