package com.marknote.app.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Stable
internal class EditorSearchState {

    var isOpen by mutableStateOf(false)
        private set

    var query by mutableStateOf("")
        private set

    var replacement by mutableStateOf("")
        private set

    var matchCase by mutableStateOf(false)
        private set

    var matchIndex by mutableIntStateOf(0)
        private set

    var queryRevision by mutableIntStateOf(0)
        private set

    var sourceMatches by mutableStateOf<List<IntRange>>(emptyList())
        internal set

    var sourceMatchesRevision by mutableIntStateOf(0)
        internal set

    var renderedMatches by mutableStateOf<List<IntRange>>(emptyList())
        internal set

    var renderedMatchesRevision by mutableIntStateOf(0)
        internal set

    private var lastAutoJumpedSourceRev = 0
    private var lastAutoJumpedRenderedRev = 0

    fun matches(isPreview: Boolean): List<IntRange> =
        if (isPreview) renderedMatches else sourceMatches

    fun clampedIndex(isPreview: Boolean): Int {
        val list = matches(isPreview)
        return matchIndex.coerceIn(0, (list.size - 1).coerceAtLeast(0))
    }

    fun currentMatch(isPreview: Boolean): IntRange? =
        matches(isPreview).getOrNull(clampedIndex(isPreview))

    fun toggle() {
        if (isOpen) {
            close()
        } else {
            isOpen = true
        }
    }

    fun close() {
        isOpen = false
        query = ""
        replacement = ""
    }

    fun updateQuery(value: String) {
        if (query == value) return
        query = value
        matchIndex = 0
        queryRevision++
    }

    fun updateReplacement(value: String) {
        replacement = value
    }

    fun toggleMatchCase() {
        matchCase = !matchCase
        matchIndex = 0
        queryRevision++
    }

    fun consumeAutoJump(isPreview: Boolean): IntRange? {
        if (query.isEmpty()) return null
        return if (isPreview) {
            if (renderedMatchesRevision == queryRevision &&
                renderedMatchesRevision != lastAutoJumpedRenderedRev &&
                renderedMatches.isNotEmpty()
            ) {
                lastAutoJumpedRenderedRev = renderedMatchesRevision
                moveTo(0, isPreview = true)
            } else {
                null
            }
        } else {
            if (sourceMatchesRevision == queryRevision &&
                sourceMatchesRevision != lastAutoJumpedSourceRev &&
                sourceMatches.isNotEmpty()
            ) {
                lastAutoJumpedSourceRev = sourceMatchesRevision
                moveTo(0, isPreview = false)
            } else {
                null
            }
        }
    }

    fun moveTo(index: Int, isPreview: Boolean): IntRange? {
        val list = matches(isPreview)
        if (list.isEmpty()) return null
        matchIndex = ((index % list.size) + list.size) % list.size
        return list[matchIndex]
    }

    fun step(delta: Int, isPreview: Boolean): IntRange? =
        moveTo(matchIndex + delta, isPreview)
}

@Composable
internal fun rememberEditorSearchState(
    uriString: String,
    sourceText: String,
    renderedText: String,
): EditorSearchState {
    val state = remember(uriString) { EditorSearchState() }

    LaunchedEffect(state, sourceText, state.query, state.matchCase) {
        val rev = state.queryRevision
        val found = if (state.query.isEmpty()) {
            emptyList()
        } else {
            withContext(Dispatchers.Default) {
                findMatches(sourceText, state.query, ignoreCase = !state.matchCase)
            }
        }
        state.sourceMatches = found
        state.sourceMatchesRevision = rev
    }

    LaunchedEffect(state, renderedText, state.query, state.matchCase) {
        val rev = state.queryRevision
        val found = if (state.query.isEmpty()) {
            emptyList()
        } else {
            withContext(Dispatchers.Default) {
                findMatches(renderedText, state.query, ignoreCase = !state.matchCase)
            }
        }
        state.renderedMatches = found
        state.renderedMatchesRevision = rev
    }

    return state
}
