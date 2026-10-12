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

    fun highlight(text: String): AnnotatedString = buildAnnotatedString {
        append(text)
        for (span in highlightRanges(text)) {
            addStyle(styleOf.getValue(span.kind), span.start, span.endExclusive)
        }
    }
}
