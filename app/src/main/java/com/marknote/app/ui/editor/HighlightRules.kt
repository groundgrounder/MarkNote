package com.marknote.app.ui.editor

/**
 * 语法高亮的**规则表**：哪段文本算什么语法元素。
 *
 * 与 [MarkdownSyntax]、[MarkdownEdit] 同一条约定：这个文件**一个 Android/Compose 的 import
 * 都不能有**，这样 tools/checks 的 JVM 断言能直接跑（见 CheckHighlightRules）——
 * 「这串文本到底会不会被标成链接」光读正则最容易看走眼，必须能在毫秒级跑一遍断言。
 *
 * 颜色与字重不在这里：那是 [MarkdownHighlighter] 按主题取的事，本文件只回答「是什么」。
 */

/** 高亮元素种类。只表达语义，不含任何样式 */
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

/** 一条规则：[group] 非 0 时只给那个捕获组上色（列表标记只给 `-` / `1.` 上色，不含行尾空白） */
data class HighlightRule(val kind: HighlightKind, val regex: Regex, val group: Int = 0)

/** 一次命中：闭开区间，可以直接喂给 `AnnotatedString.addStyle` */
data class HighlightSpan(val kind: HighlightKind, val start: Int, val endExclusive: Int)

/**
 * 规则表。**顺序即应用顺序**：大范围在前、行内元素在后，
 * 后应用的样式在重叠处覆盖先应用的（所以行内代码能盖住引用行的斜体）。
 *
 * ⚠️ 带语法符号的规则必须排除「符号前紧挨着反斜杠」的情况。Markdown 里
 * `\[`、`` \` ``、`\*`、`\~` 表示的是**字面字符**，若照旧匹配，`\[文档\](http://x)`
 * 这种本该原样显示的写法会被标成链接 —— 而预览端（Markwon）处理转义是对的，
 * 于是同一份文件在编辑态「花哨」、预览态正常，两边对不上（2026-09-20 实测）。
 *
 * 行首类规则（围栏、标题、引用、分割线、列表标记）不需要这一步：行首的位置前面
 * 不可能有反斜杠「紧挨着」——`^` 后面第一个字符就是判断对象。
 */
val highlightRules: List<HighlightRule> = listOf(
    // 围栏代码块：整段底色 + 等宽
    HighlightRule(
        HighlightKind.CODE_BLOCK,
        Regex("^```[\\s\\S]*?^```\\s*$", RegexOption.MULTILINE),
    ),
    // 标题行
    HighlightRule(
        HighlightKind.HEADING,
        Regex("^#{1,6}\\s[^\\n]*$", RegexOption.MULTILINE),
    ),
    // 引用行
    HighlightRule(
        HighlightKind.QUOTE,
        Regex("^>\\s?[^\\n]*$", RegexOption.MULTILINE),
    ),
    // 分割线
    HighlightRule(
        HighlightKind.HORIZONTAL_RULE,
        Regex("^(?:-{3,}|\\*{3,}|_{3,})$", RegexOption.MULTILINE),
    ),
    /*
     * 行内代码 —— 两个反引号都要挡转义：
     * 开头的那个自不必说；闭合的那个也要，否则 `` `a\`b` `` 会被配成 `` `a\` ``。
     * `(?<!\\)` 放在闭合反引号前，检查的是它前面的字符（也就是 `[^`\n]+` 的末位）。
     */
    HighlightRule(
        HighlightKind.INLINE_CODE,
        Regex("(?<!\\\\)`[^`\\n]+(?<!\\\\)`"),
    ),
    // 加粗
    HighlightRule(
        HighlightKind.BOLD,
        Regex("(?<!\\\\)\\*\\*[^*\\n]+\\*\\*"),
    ),
    /*
     * 斜体。前一个字符既不能是 `*`（那是加粗的开头，交给上一条规则），
     * 也不能是 `\`（那是转义）—— 所以是 `[\\*]` 而不是单个 `\*`。
     */
    HighlightRule(
        HighlightKind.ITALIC,
        Regex("(?<![\\\\*])\\*[^*\\n]+\\*(?!\\*)"),
    ),
    // 删除线
    HighlightRule(
        HighlightKind.STRIKETHROUGH,
        Regex("(?<!\\\\)~~[^~\\n]+~~"),
    ),
    /*
     * 链接。`]` 与 `(` 之间不做转义检查（那需要数反斜杠奇偶，正则里做不到），
     * 但开头和闭合 `]` 这两处挡住后，`\[文档\](url)` 与 `[文档\](url)` 都不会再命中。
     */
    HighlightRule(
        HighlightKind.LINK,
        Regex("(?<!\\\\)\\[[^]\\n]+(?<!\\\\)\\]\\([^)\\n]+\\)"),
    ),
    // 列表标记（- * + 或 1.）：只给标记本身，行尾空白不上色
    HighlightRule(
        HighlightKind.LIST_MARKER,
        Regex("^(\\s*(?:[-*+]|\\d+\\.))(?=\\s)", RegexOption.MULTILINE),
        group = 1,
    ),
)

/**
 * 扫出全文的高亮区间，按 [highlightRules] 的顺序排列（同一条规则的命中按出现位置）。
 *
 * 纯逻辑、不碰 Android：断言里直接给一段文本、比它算出来的区间，
 * 比对着正则读一遍可靠得多。
 */
fun highlightRanges(text: String): List<HighlightSpan> = buildList {
    for (rule in highlightRules) {
        for (match in rule.regex.findAll(text)) {
            val range = match.groups[rule.group]?.range ?: continue
            if (range.isEmpty()) continue
            add(HighlightSpan(rule.kind, range.first, range.last + 1))
        }
    }
}
