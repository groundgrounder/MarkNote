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
 * 轻量 Markdown 语法高亮：纯正则扫描，只做样式叠加、不改变文本长度，
 * 配合 VisualTransformation + OffsetMapping.Identity 使用。
 * 后添加的样式在重叠处优先，因此先处理大范围（代码块），再处理行内元素。
 */
class MarkdownHighlighter(private val colors: ColorScheme) {

    private data class Rule(
        val regex: Regex,
        val style: SpanStyle,
        val group: Int = 0,
    )

    private val rules by lazy {
        listOf(
            // 围栏代码块：整段底色 + 等宽
            Rule(
                Regex("^```[\\s\\S]*?^```\\s*$", RegexOption.MULTILINE),
                SpanStyle(background = colors.surfaceVariant, fontFamily = FontFamily.Monospace),
            ),
            // 标题行
            Rule(
                Regex("^#{1,6}\\s[^\\n]*$", RegexOption.MULTILINE),
                SpanStyle(color = colors.primary, fontWeight = FontWeight.Bold),
            ),
            // 引用行
            Rule(
                Regex("^>\\s?[^\\n]*$", RegexOption.MULTILINE),
                SpanStyle(color = colors.secondary, fontStyle = FontStyle.Italic),
            ),
            // 分割线
            Rule(
                Regex("^(?:-{3,}|\\*{3,}|_{3,})$", RegexOption.MULTILINE),
                SpanStyle(color = colors.outline),
            ),
            // 行内代码
            Rule(
                Regex("`[^`\\n]+`"),
                SpanStyle(background = colors.surfaceVariant, fontFamily = FontFamily.Monospace),
            ),
            // 加粗
            Rule(
                Regex("\\*\\*[^*\\n]+\\*\\*"),
                SpanStyle(fontWeight = FontWeight.Bold),
            ),
            // 斜体
            Rule(
                Regex("(?<!\\*)\\*[^*\\n]+\\*(?!\\*)"),
                SpanStyle(fontStyle = FontStyle.Italic),
            ),
            // 删除线
            Rule(
                Regex("~~[^~\\n]+~~"),
                SpanStyle(textDecoration = TextDecoration.LineThrough),
            ),
            // 链接
            Rule(
                Regex("\\[[^]\\n]+]\\([^)\\n]+\\)"),
                SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline),
            ),
            // 列表标记（- * + 或 1.）
            Rule(
                Regex("^(\\s*(?:[-*+]|\\d+\\.))(?=\\s)", RegexOption.MULTILINE),
                SpanStyle(color = colors.primary, fontWeight = FontWeight.Bold),
                group = 1,
            ),
        )
    }

    fun highlight(text: String): AnnotatedString = buildAnnotatedString {
        append(text)
        for (rule in rules) {
            for (match in rule.regex.findAll(text)) {
                val range = match.groups[rule.group]?.range ?: continue
                if (range.isEmpty()) continue
                addStyle(rule.style, range.first, range.last + 1)
            }
        }
    }
}
