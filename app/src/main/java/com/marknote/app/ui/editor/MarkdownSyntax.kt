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
 * [offset] 所在那一行是否落在围栏代码块里。
 *
 * 判断只看**该行之前**的围栏状态（本行的围栏标记算下一行的事），口径与 [parseOutline] 一致。
 * 用在「别在代码块里续列表标记」这类编辑行为上：代码块里的 `- ` 是内容，不是列表。
 */
internal fun isInsideCodeFence(text: String, offset: Int): Boolean {
    var fence: Pair<Char, Int>? = null
    var lineStart = 0
    for (line in text.split("\n")) {
        if (lineStart >= offset) break
        val trimmed = line.trimStart()
        val mark = fenceOf(trimmed)
        val current = fence
        when {
            current != null -> {
                if (mark != null && mark.first == current.first && mark.second >= current.second &&
                    trimmed.substring(mark.second).isBlank()
                ) {
                    fence = null
                }
            }
            mark != null -> fence = mark
        }
        lineStart += line.length + 1
    }
    return fence != null
}

/**
 * 界面上给人看的标题：文件名去掉 `.md` / `.markdown` 后缀。
 *
 * 编辑器顶栏标题用它。**别在别处再写一遍 `removeSuffix`**：两处各写一遍迟早分叉，而分叉的样子
 * 很难看 —— 曾经标签条漏了去后缀，同一份文档顶栏写 `tabA`、标签条写 `tabA.md`，像两份不同的文件。
 *
 * 只认小写后缀（与顶栏一直以来的行为一致）：`README.MD` 保留后缀不变。
 */
fun fileTitle(name: String): String = name.removeSuffix(".md").removeSuffix(".markdown")

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

// ---------------------------------------------------------------- 渲染前的两处保真处理
//
// 这两处都是「Markdown 语法把正文吃掉了」：脚注定义行会被 CommonMark 当成**链接引用定义**
// 整行消失（实测：`[^1]: 脚注内容` 在预览里一个字都不剩），裸 URL 在 GFM 里本该自动成链
// 而 Markwon 不会。两条都在**渲染前**对源码做处理，这样预览文本与源码逐字对得上，
// 搜索仍能搜到原样文字。

/** 片段类型：[TEXT] 是普通正文（两处处理只动它），[CODE] 是围栏/行内代码，[LINK] 是链接构造 */
private enum class SegmentKind { TEXT, CODE, LINK }

/** 整篇 Markdown 切出的片段，[start] / [end] 是全文绝对偏移（不含行尾换行） */
private data class Segment(val kind: SegmentKind, val start: Int, val end: Int)

/** 行首的链接引用定义 `[label]: destination` —— CommonMark 会把整行吃掉 */
private val referenceDefinition = Regex("""^ {0,3}\[[^\]]+\]:""")

/**
 * 行首的**脚注**定义 `[^1]: …`。
 *
 * ⚠️ 必须与上一条分开：CommonMark 同样会把脚注定义当链接引用定义吃整行，但它恰恰是
 * [keepFootnoteMarkersLiteral] 要救的那一行。归进 LINK 段就没人处理它了（实测：断言直接红）。
 * 归成正文之后，转义先把它变成普通文字，autolink 也能照常处理定义文字里的裸 URL。
 */
private val footnoteDefinition = Regex("""^ {0,3}\[\^[^\]]*]:""")

/**
 * 把 Markdown 切成三类片段。
 *
 * 为什么要分类：处理只能落在正文上 —— 往代码块或链接目标里塞 `<…>` / `\` 会当场改坏内容。
 * 围栏沿用 [fenceOf] 的口径（与大纲一致），行内片段在 [scanInline] 里扫。
 */
private fun markdownSegments(markdown: String): List<Segment> {
    val out = mutableListOf<Segment>()
    var offset = 0
    var fence: Pair<Char, Int>? = null
    for (line in markdown.split("\n")) {
        val lineStart = offset
        val lineEnd = offset + line.length
        val trimmed = line.trimStart()
        val fenceMark = fenceOf(trimmed)
        val current = fence
        when {
            current != null -> {
                out.add(Segment(SegmentKind.CODE, lineStart, lineEnd))
                if (fenceMark != null && fenceMark.first == current.first &&
                    fenceMark.second >= current.second && trimmed.substring(fenceMark.second).isBlank()
                ) {
                    fence = null
                }
            }
            fenceMark != null -> {
                fence = fenceMark
                out.add(Segment(SegmentKind.CODE, lineStart, lineEnd))
            }
            referenceDefinition.containsMatchIn(line) && !footnoteDefinition.containsMatchIn(line) ->
                out.add(Segment(SegmentKind.LINK, lineStart, lineEnd))
            else -> scanInline(markdown, lineStart, lineEnd, out)
        }
        offset = lineEnd + 1
    }
    return out
}

/** 扫一行：行内代码、链接/图片构造、`<…>` 各自成段，其余归正文 */
private fun scanInline(text: String, lineStart: Int, lineEnd: Int, out: MutableList<Segment>) {
    var i = lineStart
    var textStart = lineStart
    fun flush(end: Int) {
        if (end > textStart) out.add(Segment(SegmentKind.TEXT, textStart, end))
    }
    while (i < lineEnd) {
        val c = text[i]
        if (c == '`') {
            // CommonMark：同样长度的反引号串才算闭合；找不到就当普通正文
            var run = 0
            while (i + run < lineEnd && text[i + run] == '`') run++
            val closer = findBacktickRun(text, i + run, lineEnd, run)
            if (closer >= 0) {
                flush(i)
                out.add(Segment(SegmentKind.CODE, i, closer + run))
                i = closer + run
                textStart = i
                continue
            }
            i += run
            continue
        }
        if (c == '<') {
            val gt = text.indexOf('>', i + 1)
            if (gt in (i + 1) until lineEnd) {
                flush(i)
                out.add(Segment(SegmentKind.LINK, i, gt + 1))
                i = gt + 1
                textStart = i
                continue
            }
            i++
            continue
        }
        if (c == '[' || (c == '!' && i + 1 < lineEnd && text[i + 1] == '[')) {
            val bracket = if (c == '!') i + 1 else i
            val closeBracket = matchingBracket(text, bracket, lineEnd)
            if (closeBracket > 0 && closeBracket + 1 < lineEnd && text[closeBracket + 1] == '(') {
                val closeParen = matchingParen(text, closeBracket + 1, lineEnd)
                if (closeParen > 0) {
                    flush(i)
                    out.add(Segment(SegmentKind.LINK, i, closeParen + 1))
                    i = closeParen + 1
                    textStart = i
                    continue
                }
            }
            i++
            continue
        }
        i++
    }
    flush(lineEnd)
}

private fun findBacktickRun(text: String, from: Int, limit: Int, run: Int): Int {
    var i = from
    while (i < limit) {
        if (text[i] == '`') {
            var len = 0
            while (i + len < limit && text[i + len] == '`') len++
            if (len == run) return i
            i += len
        } else {
            i++
        }
    }
    return -1
}

/** `[` 对应的 `]`（允许嵌套一层方括号，够 Markdown 里常见的 `[a [b]]` 用） */
private fun matchingBracket(text: String, open: Int, limit: Int): Int {
    var depth = 0
    var i = open
    while (i < limit) {
        when (text[i]) {
            '[' -> depth++
            ']' -> {
                depth--
                if (depth == 0) return i
            }
        }
        i++
    }
    return -1
}

/** `(` 对应的 `)`，认一层嵌套（URL 里带括号的情况） */
private fun matchingParen(text: String, open: Int, limit: Int): Int {
    var depth = 0
    var i = open
    while (i < limit) {
        when (text[i]) {
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) return i
            }
        }
        i++
    }
    return -1
}

/**
 * 脚注标记原样保留：`[^1]` 引用与 `[^1]: 定义` 里的 `[` 转义一下。
 *
 * 不处理的话，CommonMark 会把 `[^1]: 脚注内容` 整个当成链接引用定义 —— 那行在预览里**整行消失**，
 * 引用处也只剩 `^1`（方括号被当语法吃掉）。转义后两处都按字面显示，与源码逐字一致。
 * 真正的脚注渲染（编号 + 文末列表）是另一件事，这里只保证「不丢字」。
 */
internal fun keepFootnoteMarkersLiteral(markdown: String): String {
    if (!markdown.contains("[^")) return markdown
    return mapTextSegments(markdown) { it.replace("[^", "\\[^") }
}

/** 裸 URL 自动成链（GFM autolink 的常见那半边）：只认 `http://` / `https://` */
private val bareUrlPattern = Regex("""https?://[^\s<>\[\]"'`]+""")

/**
 * 尾随标点里**不算 URL** 的那些 —— 直接照 GFM 的集合来：`?!.,:*_~`（外加引号）。
 *
 * ⚠️ `*` `_` `~` 三个必须带上：漏掉时 `**https://a.com**` 这种「强调包住链接」的写法，
 * 尾部的 `**` 会被吃进链接，链接和加粗同时坏掉（断言先红了才发现）。
 * `_` 只在**结尾处**剥，所以 `https://a.com/a_b` 这类中间带下划线的 URL 不受影响。
 *
 * 与 GFM 一致的边界：`https://a.com**粗**` 这种「链接后紧跟强调」的夹心写法，
 * GFM 与我们都只剥到 `粗` 为止（链接会是 `https://a.com**粗`）—— 这是标准的脾气，
 * 不是实现取舍；想分开写就加个空格。
 */
private const val URL_TAIL_TRIM = ".,;:!?\"'*_~"

/**
 * 把正文里的裸 URL 包成 `<url>`，交给 Markwon 现有链路渲染 —— 与源码只差两个尖括号，
 * 显示出来的文字一模一样，所以预览里的搜索照常能搜到这个 URL。
 *
 * 只动 [SegmentKind.TEXT]：代码里的 URL 不碰（否则代码块会被改成 `<…>` 写法），
 * 链接/图片构造整段跳过（往 `](…)` 里塞尖括号会当场把链接改坏）。
 */
internal fun autolinkBareUrls(markdown: String): String {
    if (!markdown.contains("http")) return markdown
    return mapTextSegments(markdown) { segment ->
        bareUrlPattern.replace(segment) { m ->
            val url = trimUrlTail(m.value)
            if (url.isEmpty()) m.value else "<$url>" + m.value.substring(url.length)
        }
    }
}

/** 去掉 GFM 不算进 URL 的尾随标点；`)` 只有不配对时才剥（`…/Foo_(bar)` 要保留） */
private fun trimUrlTail(url: String): String {
    var end = url.length
    while (end > 0 && URL_TAIL_TRIM.indexOf(url[end - 1]) >= 0) end--
    while (end > 0 && url[end - 1] == ')') {
        val opens = url.take(end).count { it == '(' }
        val closes = url.take(end).count { it == ')' }
        if (closes > opens) end-- else break
    }
    return url.substring(0, end)
}

/** 只替换正文片段，其余原样拷贝 */
private fun mapTextSegments(markdown: String, transform: (String) -> String): String {
    val segments = markdownSegments(markdown)
    if (segments.isEmpty()) return transform(markdown)
    val out = StringBuilder(markdown.length + 16)
    var cursor = 0
    for (segment in segments) {
        if (segment.start > cursor) out.append(markdown, cursor, segment.start)
        val slice = markdown.substring(segment.start, segment.end)
        out.append(if (segment.kind == SegmentKind.TEXT) transform(slice) else slice)
        cursor = segment.end
    }
    if (cursor < markdown.length) out.append(markdown, cursor, markdown.length)
    return out.toString()
}

/** 渲染前对源码做的全部保真处理，按顺序走一遍 */
internal fun preparePreviewMarkdown(markdown: String): String =
    autolinkBareUrls(keepFootnoteMarkersLiteral(markdown))

// ---------------------------------------------------------------- 渲染后的两处改写

/** 渲染文本里的一处任务列表标记：`[ ]` / `[x]` 三个字符的位置与勾选状态 */
internal data class TaskBox(val start: Int, val end: Int, val checked: Boolean)

/**
 * 找出渲染文本里的任务列表标记。
 *
 * 按**渲染后**的文本找：列表标记（`- `）在渲染时被 BulletSpan 吃掉了，所以任务项的行首就是
 * `[ ] ` / `[x] `（实测 dump：`[ ] 未完成的任务`）。缩进子项前面可能有空格，允许。
 */
private val taskBoxPattern = Regex("""(?m)^([ \t]*)\[([ xX])](?=\s|$)""")

internal fun taskBoxRanges(renderedText: String): List<TaskBox> =
    taskBoxPattern.findAll(renderedText).map { m ->
        val start = m.range.first + m.groupValues[1].length
        TaskBox(start, start + 3, m.groupValues[2] != " ")
    }.toList()

/**
 * GitHub 的标题锚点规则：小写、去掉标点、空格换成连字符，**中日韩文字保留**。
 * `## 更新余地（规划）` → `更新余地规划`。
 */
internal fun anchorSlug(title: String): String {
    val out = StringBuilder(title.length)
    for (ch in title.trim().lowercase()) {
        when {
            ch.isLetterOrDigit() -> out.append(ch)
            ch == ' ' || ch == '\t' -> out.append('-')
            ch == '-' || ch == '_' -> out.append(ch)
        }
    }
    return out.toString()
}

/**
 * 按锚点找标题：先比 slug，再比原文字（大小写不敏感），最后忽略重名后缀 `-1` / `-2` 再比一次。
 * 大小写与 URL 编码（`%E6%A0%87%E9%A2%98`）都认 —— 手写锚点和编辑器自动补的编码形式都会出现。
 */
internal fun findHeadingByAnchor(headings: List<Heading>, anchor: String): Heading? {
    val raw = anchor.removePrefix("#")
    if (raw.isEmpty()) return null
    val decoded = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    for (candidate in listOf(decoded, raw)) {
        val slug = candidate.lowercase()
        headings.firstOrNull { anchorSlug(plainTitle(it.title)) == slug }?.let { return it }
        headings.firstOrNull { plainTitle(it.title).lowercase() == slug }?.let { return it }
    }
    val stripped = decoded.replace(Regex("""-\d+$"""), "")
    if (stripped != decoded && stripped.isNotEmpty()) {
        val slug = stripped.lowercase()
        headings.firstOrNull { anchorSlug(plainTitle(it.title)) == slug }?.let { return it }
        headings.firstOrNull { plainTitle(it.title).lowercase() == slug }?.let { return it }
    }
    return null
}
