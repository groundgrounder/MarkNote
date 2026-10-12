package com.marknote.app.ui.editor

data class TextEdit(
    val start: Int,
    val removed: String,
    val inserted: String,
) {

    val caretAfter: Int get() = start + inserted.length

    val caretBefore: Int get() = start + removed.length
}

data class UndoOutcome(val text: String, val caret: Int)

class UndoStack @JvmOverloads constructor(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxChars: Int = DEFAULT_MAX_CHARS,
    private val coalesceMs: Long = DEFAULT_COALESCE_MS,
) {

    private val undoList = ArrayList<TextEdit>()
    private val redoList = ArrayList<TextEdit>()

    private var totalChars = 0

    private var lastAtMs = Long.MIN_VALUE

    val canUndo: Boolean get() = undoList.isNotEmpty()

    val canRedo: Boolean get() = redoList.isNotEmpty()

    val undoDepth: Int get() = undoList.size

    fun clear() {
        undoList.clear()
        redoList.clear()
        totalChars = 0
        lastAtMs = Long.MIN_VALUE
    }

    @JvmOverloads
    fun record(oldText: String, newText: String, nowMs: Long, coalesce: Boolean = true) {
        if (oldText == newText) return
        val edit = diff(oldText, newText)

        redoList.clear()

        val inBurst = coalesce && lastAtMs != Long.MIN_VALUE && nowMs - lastAtMs <= coalesceMs
        val prev = if (inBurst) undoList.lastOrNull() else null
        if (prev != null) {
            val merged = tryMerge(prev, edit)
            if (merged != null) {
                totalChars -= prev.removed.length + prev.inserted.length
                undoList[undoList.size - 1] = merged
                totalChars += merged.removed.length + merged.inserted.length
                lastAtMs = nowMs
                trim()
                return
            }
        }
        undoList.add(edit)
        totalChars += edit.removed.length + edit.inserted.length

        lastAtMs = if (coalesce) nowMs else Long.MIN_VALUE
        trim()
    }

    fun undo(text: String): UndoOutcome? {
        val edit = undoList.lastOrNull() ?: return null

        if (!matches(text, edit, forward = false)) {
            clear()
            return null
        }
        undoList.removeAt(undoList.size - 1)
        totalChars -= edit.removed.length + edit.inserted.length
        redoList.add(edit)
        lastAtMs = Long.MIN_VALUE
        return UndoOutcome(apply(text, edit, forward = false), edit.caretBefore)
    }

    fun redo(text: String): UndoOutcome? {
        val edit = redoList.lastOrNull() ?: return null
        if (!matches(text, edit, forward = true)) {
            clear()
            return null
        }
        redoList.removeAt(redoList.size - 1)
        totalChars += edit.removed.length + edit.inserted.length
        undoList.add(edit)
        lastAtMs = Long.MIN_VALUE
        return UndoOutcome(apply(text, edit, forward = true), edit.caretAfter)
    }

    private fun trim() {
        while (undoList.isNotEmpty() && (undoList.size > maxEntries || totalChars > maxChars)) {
            val oldest = undoList.removeAt(0)
            totalChars -= oldest.removed.length + oldest.inserted.length
        }
    }

    companion object {

        const val DEFAULT_MAX_ENTRIES = 200

        const val DEFAULT_MAX_CHARS = 500_000

        const val DEFAULT_COALESCE_MS = 600L

        @JvmStatic
        fun diff(oldText: String, newText: String): TextEdit {
            var prefix = 0
            val maxPrefix = minOf(oldText.length, newText.length)
            while (prefix < maxPrefix && oldText[prefix] == newText[prefix]) prefix++

            var suffix = 0
            val maxSuffix = minOf(oldText.length, newText.length) - prefix
            while (suffix < maxSuffix &&
                oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
            ) suffix++

            return TextEdit(
                start = prefix,
                removed = oldText.substring(prefix, oldText.length - suffix),
                inserted = newText.substring(prefix, newText.length - suffix),
            )
        }

        @JvmStatic
        fun apply(text: String, edit: TextEdit, forward: Boolean): String {
            val from = if (forward) edit.removed else edit.inserted
            val to = if (forward) edit.inserted else edit.removed
            val at = edit.start
            return text.substring(0, at) + to + text.substring(at + from.length)
        }

        @JvmStatic
        fun matches(text: String, edit: TextEdit, forward: Boolean): Boolean {
            val from = if (forward) edit.removed else edit.inserted
            val at = edit.start
            if (at < 0 || at > text.length) return false
            if (at + from.length > text.length) return false

            return from.isEmpty() || text.regionMatches(at, from, 0, from.length)
        }

        private fun tryMerge(prev: TextEdit, next: TextEdit): TextEdit? = when {
            prev.removed.isEmpty() && next.removed.isEmpty() &&
                next.start == prev.start + prev.inserted.length ->
                TextEdit(prev.start, "", prev.inserted + next.inserted)

            prev.inserted.isEmpty() && next.inserted.isEmpty() &&
                next.start + next.removed.length == prev.start ->
                TextEdit(next.start, next.removed + prev.removed, "")

            else -> null
        }
    }
}
