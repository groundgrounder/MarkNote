package com.marknote.app.ui.editor

enum class HighlightKind {
    CODE_BLOCK,
    HEADING,
    QUOTE,
    HORIZONTAL_RULE,
    INLINE_CODE,
    BOLD,
    ITALIC,
    STRIKETHROUGH,
    LINK,
    LIST_MARKER,
}

data class HighlightRule(val kind: HighlightKind, val regex: Regex, val group: Int = 0)

data class HighlightSpan(val kind: HighlightKind, val start: Int, val endExclusive: Int)

val highlightRules: List<HighlightRule> = listOf(

    HighlightRule(
        HighlightKind.CODE_BLOCK,
        Regex("^(?:```[\\s\\S]*?^```\\s*$|~~~[\\s\\S]*?^~~~\\s*$)", RegexOption.MULTILINE),
    ),

    HighlightRule(
        HighlightKind.HEADING,
        Regex("^#{1,6}\\s[^\\n]*$", RegexOption.MULTILINE),
    ),

    HighlightRule(
        HighlightKind.QUOTE,
        Regex("^>\\s?[^\\n]*$", RegexOption.MULTILINE),
    ),

    HighlightRule(
        HighlightKind.HORIZONTAL_RULE,
        Regex("^(?:-{3,}|\\*{3,}|_{3,})$", RegexOption.MULTILINE),
    ),

    HighlightRule(
        HighlightKind.INLINE_CODE,
        Regex("(?<!\\\\)`[^`\\n]+(?<!\\\\)`"),
    ),

    HighlightRule(
        HighlightKind.BOLD,
        Regex("(?<!\\\\)\\*\\*[^*\\n]+\\*\\*"),
    ),

    HighlightRule(
        HighlightKind.ITALIC,
        Regex("(?<![\\\\*])\\*[^*\\n]+\\*(?!\\*)"),
    ),

    HighlightRule(
        HighlightKind.STRIKETHROUGH,
        Regex("(?<!\\\\)~~[^~\\n]+~~"),
    ),

    HighlightRule(
        HighlightKind.LINK,
        Regex("(?<!\\\\)\\[[^]\\n]+(?<!\\\\)\\]\\([^)\\n]+\\)"),
    ),

    HighlightRule(
        HighlightKind.LIST_MARKER,
        Regex("^(\\s*(?:[-*+]|\\d+\\.))(?=\\s)", RegexOption.MULTILINE),
        group = 1,
    ),
)

fun highlightRanges(text: String): List<HighlightSpan> = buildList {
    for (rule in highlightRules) {
        for (match in rule.regex.findAll(text)) {
            val range = match.groups[rule.group]?.range ?: continue
            if (range.isEmpty()) continue
            add(HighlightSpan(rule.kind, range.first, range.last + 1))
        }
    }
}
