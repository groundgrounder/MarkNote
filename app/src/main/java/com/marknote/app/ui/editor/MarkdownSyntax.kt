package com.marknote.app.ui.editor

/**
 * Markdown 的**纯文本解析**：大纲、搜索命中、标题的「源码 → 渲染文本」换算。
 *
 * 单独一个文件、且**一个 import 都没有** —— 与 [LatexMath] 同一条约定：这里的函数
 * 全都能在 JVM 上直接断言（见 tools/checks 的 CheckEncodingOutline / CheckFindMatches /
 * CheckHeadingOffset），不用起模拟器，也不需要 JUnit。
 *
 * ⚠️ **不要再把它们挪回 EditorViewModel.kt / EditorScreen.kt**。那两个文件 import 了
 * androidx，断言之所以还能跑，靠的是「JVM 只加载被调到的那几个方法」这个侥幸前提：
 * 在这里顺手加一行 `android.util.Log.d` 就会让 check 以 `NoClassDefFoundError` 崩掉 ——
 * 一个和改动本身毫无关系的报错，排查起来很费劲。（2026-09-16 从两个文件里搬出来。）
 */

/** 一个大纲条目：标题层级、文字、在全文中的字符偏移 */
data class Heading(val level: Int, val title: String, val offset: Int)

/**
 * 从 Markdown 全文解析标题大纲（跳过代码块内部）。
 *
 * 围栏两种都认（``` 与 ~~~，CommonMark 皆然），且**只有同种字符、长度不短于开头**才算闭合。
 * 早期只写死 ` ``` ` 且一遇到就取反，于是 `~~~` 块会被当成正文（里面的 `#` 进了大纲），
 * 而 `~~~` 后面那个 ``` 又会被误当成闭合，把真正的正文当成代码块跳过。
 *
 * 四空格缩进的代码块没有处理：那需要完整的块级解析，而误判的代价只是大纲里多一条、
 * 少一条，不值得把一整条 Markdown 解析链引进来。
 */
fun parseOutline(text: String): List<Heading> {
    val result = mutableListOf<Heading>()
    val headingRegex = Regex("^(#{1,6})\\s+(.+?)\\s*$")
    var offset = 0
    // 当前代码围栏：字符（` 或 ~）与开头的连续长度；null 表示不在围栏里
    var fenceChar: Char? = null
    var fenceLen = 0
    for (line in text.split("\n")) {
        val trimmed = line.trimStart()
        if (fenceChar == null) {
            val open = fenceOf(trimmed)
            if (open != null) {
                fenceChar = open.first
                fenceLen = open.second
            } else {
                val m = headingRegex.matchEntire(line)
                if (m != null) {
                    result.add(Heading(m.groupValues[1].length, m.groupValues[2], offset))
                }
            }
        } else {
            // 闭合行必须只有围栏字符本身（后面至多留空白），否则 ```` ```foo ```` 会误闭合
            val close = fenceOf(trimmed)
            if (close != null && close.first == fenceChar && close.second >= fenceLen &&
                trimmed.substring(close.second).isBlank()
            ) {
                fenceChar = null
                fenceLen = 0
            }
        }
        offset += line.length + 1
    }
    return result
}

/** 这行是不是代码围栏，是则返回（围栏字符, 连续长度） */
private fun fenceOf(line: String): Pair<Char, Int>? {
    val c = line.firstOrNull() ?: return null
    if (c != '`' && c != '~') return null
    val len = line.takeWhile { it == c }.length
    if (len < 3) return null
    // 反引号围栏的信息串里不允许再出现反引号（CommonMark），`~~~` 无此限制
    if (c == '`' && line.substring(len).contains('`')) return null
    return c to len
}

/**
 * 找出 query 的全部**非重叠**命中区间。
 *
 * 口径必须和 `EditorViewModel.replaceAll` 一致（那里也按非重叠推进）：早期这里用
 * `indexOf(query, i + 1)` 会数出重叠命中，于是 `aaaa` 里搜 `aa` 显示 3 处、
 * 「全部替换」却只换掉 2 处——用户看到的数字和实际结果对不上。
 */
internal fun findMatches(text: String, query: String): List<IntRange> {
    if (query.isEmpty() || text.isEmpty()) return emptyList()
    val result = mutableListOf<IntRange>()
    var i = text.indexOf(query)
    while (i >= 0) {
        result.add(i until i + query.length)
        i = text.indexOf(query, i + query.length)
    }
    return result
}

/** 标题里可能带行内标记（**加粗**、[文字](链接) 等），渲染后会消失，比较前先剥掉 */
private val inlineMarkdownPattern = Regex("""\[([^\]]*)\]\([^)]*\)|[*_~`]""")

/**
 * 标题的源码 → 它在渲染文本里长什么样。
 * internal 而非 private：纯逻辑，交给 tools/checks 断言（见 CheckHeadingOffset / CheckLatexMath）。
 *
 * 公式要**先摘出来、再处理行内标记**：公式源里本来就带 `*` `_` `` ` `` 这些字符
 * （`$$a*b$$`、`$x_1$`），先走行内标记规则会把它们吃掉，算出来的标题就跟渲染文本对不上，
 * 于是 [renderedOffsetOfHeading] 里的 indexOf 落空、大纲跳转静默退化成「大概位置」。
 * 所以这里用占位符把公式挡在行内标记处理之外，最后再放回去。
 *
 * 两种公式写法都要认（`$$…$$` 与单 `$…$`）：pattern 与渲染时的判定共用，见 [LatexMath]。
 */
internal fun plainTitle(title: String): String {
    val math = mutableListOf<String>()
    val masked = mathSegmentPattern.replace(title) { m ->
        // group 1 是 `$$…$$` 的公式源，group 2 是单 `$…$` 的；不匹配的那个 group 为空串
        math.add(m.groupValues[1].ifEmpty { m.groupValues[2] }.trim())
        "\u0000"
    }.replace(inlineMarkdownPattern) { it.groupValues[1] }
    if (math.isEmpty()) return masked.trim()
    val out = StringBuilder(masked.length)
    var next = 0
    for (ch in masked) {
        if (ch == '\u0000') out.append(math[next++]) else out.append(ch)
    }
    return out.toString().trim()
}

/**
 * 大纲里的源文本偏移 → 渲染文本偏移。
 *
 * Markdown 语法在渲染后会消失（`# 标题` 变成 `标题`、列表标记等也不占字符），
 * 所以两套偏移并不一致，不能直接拿去滚动。这里按「源文本长度比例」估算一个大概位置，
 * 再在渲染文本里找离它最近的一次标题文字；标题文字找不到（含行内标记等）时就用估算值。
 */
internal fun renderedOffsetOfHeading(
    headingOffset: Int,
    sourceText: String,
    renderedText: String,
): Int {
    if (renderedText.isEmpty()) return 0
    val ratio = if (sourceText.isEmpty()) 0.0 else headingOffset.toDouble() / sourceText.length
    val estimate = (ratio * renderedText.length).toInt().coerceIn(0, renderedText.length)
    val headingLine = sourceText.substring(headingOffset).lineSequence().firstOrNull().orEmpty()
    val title = plainTitle(headingLine.trimStart('#').trim())
    if (title.isBlank()) return estimate
    var best = -1
    var bestDistance = Int.MAX_VALUE
    var i = renderedText.indexOf(title)
    while (i >= 0) {
        val distance = kotlin.math.abs(i - estimate)
        if (distance < bestDistance) {
            bestDistance = distance
            best = i
        }
        i = renderedText.indexOf(title, i + 1)
    }
    return if (best >= 0) best else estimate
}
