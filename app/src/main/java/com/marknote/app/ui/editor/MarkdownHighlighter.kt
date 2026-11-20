package com.marknote.app.ui.editor

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

class MarkdownHighlighter(private val colors: ColorScheme) {

    private val styleOf: Map<HighlightKind, SpanStyle> by lazy {
        mapOf(
            HighlightKind.CODE_BLOCK to SpanStyle(
                background = colors.surfaceVariant,
                fontFamily = FontFamily.Monospace,
            ),
            HighlightKind.HEADING to SpanStyle(
                color = colors.primary,
                fontWeight = FontWeight.Bold,
            ),
            HighlightKind.QUOTE to SpanStyle(
                color = colors.secondary,
                fontStyle = FontStyle.Italic,
            ),
            HighlightKind.HORIZONTAL_RULE to SpanStyle(color = colors.outline),
            HighlightKind.INLINE_CODE to SpanStyle(
                background = colors.surfaceVariant,
                fontFamily = FontFamily.Monospace,
            ),
            HighlightKind.BOLD to SpanStyle(fontWeight = FontWeight.Bold),
            HighlightKind.ITALIC to SpanStyle(fontStyle = FontStyle.Italic),
            HighlightKind.STRIKETHROUGH to SpanStyle(textDecoration = TextDecoration.LineThrough),
            HighlightKind.LINK to SpanStyle(
                color = colors.primary,
                textDecoration = TextDecoration.Underline,
            ),
            HighlightKind.LIST_MARKER to SpanStyle(
                color = colors.primary,
                fontWeight = FontWeight.Bold,
            ),
        )
    }

    private val searchMatchStyle by lazy {
        SpanStyle(background = colors.primaryContainer, color = colors.onPrimaryContainer)
    }

    private val currentMatchStyle by lazy {
        SpanStyle(background = colors.primary, color = colors.onPrimary)
    }

    fun highlight(text: String): AnnotatedString = build(text, highlightRanges(text))

    fun build(
        text: String,
        spans: List<HighlightSpan>,
        searchMatches: List<IntRange> = emptyList(),
        currentMatch: IntRange? = null,
    ): AnnotatedString = buildAnnotatedString {
        append(text)
        val len = text.length
        for (span in spans) {
            val start = span.start.coerceIn(0, len)
            val end = span.endExclusive.coerceIn(start, len)
            if (start < end) addStyle(styleOf.getValue(span.kind), start, end)
        }
        for (range in searchMatches) {
            val start = range.first.coerceIn(0, len)
            val end = (range.last + 1).coerceIn(start, len)
            if (start >= end) continue
            val isCurrent = currentMatch != null && currentMatch.first == range.first
            addStyle(if (isCurrent) currentMatchStyle else searchMatchStyle, start, end)
        }
    }
}
