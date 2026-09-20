package com.marknote.app.ui.editor

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/**
 * 轻量 Markdown 语法高亮：纯样式叠加，**不改变文本长度**，
 * 配合 VisualTransformation + OffsetMapping.Identity 使用。
 *
 * 「哪段文本算什么语法元素」在 HighlightRules.kt（纯逻辑、可 JVM 断言，见 CheckHighlightRules）；
 * 这里只把 [HighlightKind] 映射成当前主题下的 [SpanStyle]。这样拆开是因为正则是看代码
 * 最容易看走眼的地方 —— 转义盲区就是这么漏过去的（2026-09-20）。
 *
 * ⚠️ 调用方必须**只按 [colors] remember 一个实例**（见 EditorScreen）：
 * 样式表是 lazy 的，而规则表是全项目共用的一份顶层值，不会因为重组而重编正则。
 */
class MarkdownHighlighter(private val colors: ColorScheme) {

    /** 种类 → 样式。每个 [HighlightKind] 都必须在这里有对应项：漏了会立刻抛，不会静默不上色 */
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
