package com.marknote.app.ui.editor

/**
 * 编辑器的**纯文本编辑行为**：回车续列表、Tab 缩进/反缩进。
 *
 * 与 [MarkdownSyntax] 同一条约定：这个文件**一个 import 都不能加**（除了 kotlin 标准库），
 * 这样 tools/checks 的 JVM 断言能直接跑（见 CheckMarkdownEdit）——「按了回车之后光标该在哪」
 * 这种事只靠读代码最容易看走眼，必须能在毫秒级跑一遍断言。
 */

/** 一次正文改写：新的全文 + 新的选区（全文绝对偏移，闭开区间） */
internal data class EditResult(val text: String, val selectionStart: Int, val selectionEnd: Int)

/** 缩进单位：两个空格（Markdown 里最通用，列表嵌套两格也能对齐） */
private const val INDENT = 2

private val bulletItem = Regex("""^([ \t]*)([-*+])([ \t]+)(\[[ xX]\])([ \t]+)?""")
private val plainBullet = Regex("""^([ \t]*)([-*+])([ \t]+)""")
private val orderedItem = Regex("""^([ \t]*)(\d+)([.)])([ \t]+)""")
private val quoteItem = Regex("""^([ \t]*)(>+)([ \t]*)""")

/**
 * 在刚按下回车（[caret] 位于新插入的 `\n` 之后）时，按上一行的写法续上标记。
 *
 * 规则（与主流编辑器一致）：
 * - `- foo` → 新行 `- `
 * - `- [x] foo` → 新行 `- [ ] `（续出来的条目一律未勾选）
 * - `1. foo` → 新行 `2. `（序号递增；`1)` 这种分隔符保持一致）
 * - `> foo` → 新行 `> `
 * - **上一行只有标记没有内容**（`- ` / `1. ` / `> `）→ 删除那个标记，即「再按一次回车结束列表」
 * - 没有标记、或在围栏代码块里 → 返回 null（交给默认行为）
 *
 * 缩进会被保留：`  - foo` 续出来的是 `  - `。
 */
internal fun continueListOnNewline(text: String, caret: Int): EditResult? {
    if (caret <= 0 || caret > text.length) return null
    val lineBreak = caret - 1
    if (text[lineBreak] != '\n') return null
    val prevStart = text.lastIndexOf('\n', lineBreak - 1) + 1
    val prevLine = text.substring(prevStart, lineBreak)
    if (isInsideCodeFence(text, prevStart)) return null

    val marker = markerFor(prevLine) ?: return null
    if (marker.blank) {
        // 空条目：把标记删掉（保留刚敲下的换行），光标留在空行开头
        return EditResult(
            text = text.removeRange(prevStart, lineBreak),
            selectionStart = prevStart,
            selectionEnd = prevStart,
        )
    }
    val insert = marker.next
    return EditResult(
        text = text.substring(0, caret) + insert + text.substring(caret),
        selectionStart = caret + insert.length,
        selectionEnd = caret + insert.length,
    )
}

/** 上一行的标记：[blank] 表示这一行除了标记没有别的内容，[next] 是下一行要续上的前缀 */
private class Marker(val blank: Boolean, val next: String)

private fun markerFor(line: String): Marker? {
    // ⚠️ 顺序要紧：带复选框的写法必须**先**判，否则 `- [ ] foo` 会被纯列表规则先吃掉
    // （纯列表规则只看 `- ` 前缀），续出来的新条目就丢掉复选框了
    bulletItem.find(line)?.let { m ->
        // group 4 是 `[ ]` / `[x]`：续出来的新条目一律是未勾选的
        val rest = line.substring(m.value.length)
        return Marker(
            blank = rest.isBlank(),
            next = m.groupValues[1] + m.groupValues[2] + " " + "[ ] ",
        )
    }
    plainBullet.find(line)?.let { m ->
        val rest = line.substring(m.value.length)
        return Marker(blank = rest.isBlank(), next = m.groupValues[1] + m.groupValues[2] + " ")
    }
    orderedItem.find(line)?.let { m ->
        val rest = line.substring(m.value.length)
        if (rest.isBlank()) return Marker(blank = true, next = "")
        val nextNumber = m.groupValues[2].toLongOrNull()?.plus(1)?.toString() ?: return null
        return Marker(
            blank = false,
            next = m.groupValues[1] + nextNumber + m.groupValues[3] + " ",
        )
    }
    quoteItem.find(line)?.let { m ->
        val rest = line.substring(m.value.length)
        return Marker(
            blank = rest.isBlank(),
            next = m.groupValues[1] + m.groupValues[2] + " ",
        )
    }
    return null
}

/**
 * Tab / Shift+Tab：对**选区覆盖到的每一行**加/减一个缩进单位。
 *
 * 几点口径：
 * - 选区停在行首时不含下一行（`end` 正好落在 `\n` 之后就把范围收回来），否则「选中一行按 Tab」
 *   会把下一行也缩进——这是很多编辑器都有的老毛病
 * - 反缩进最多吃掉两格，且**不吃 Tab 字符**：本编辑器的正文里不产生 Tab（缩进一律用空格）
 * - 没有任何一行发生变化时返回 null，调用方据此判断「这一下按了等于没按」
 */
internal fun indentLines(text: String, selectionStart: Int, selectionEnd: Int, outdent: Boolean): EditResult? {
    val start = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
    val end = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)
    val collapsed = start == end
    val rangeStart = text.lastIndexOf('\n', start - 1) + 1
    var rangeEnd = if (end > rangeStart && text[end - 1] == '\n') end - 1 else end
    if (rangeEnd < rangeStart) rangeEnd = rangeStart

    val out = StringBuilder(text.length + 16)
    out.append(text, 0, rangeStart)
    val shifts = mutableListOf<Pair<Int, Int>>() // 行首偏移 → 该行长度变化
    var lineStart = rangeStart
    var tail = rangeEnd
    var changed = false
    while (lineStart <= rangeEnd) {
        val newline = text.indexOf('\n', lineStart)
        val last = newline < 0 || newline > rangeEnd
        val lineEnd = if (last) rangeEnd else newline
        val line = text.substring(lineStart, lineEnd)
        if (outdent) {
            val spaces = line.takeWhile { it == ' ' }.length
            val remove = spaces.coerceAtMost(INDENT)
            out.append(line, remove, line.length)
            if (remove > 0) {
                shifts += lineStart to -remove
                changed = true
            }
        } else {
            out.append(" ".repeat(INDENT)).append(line)
            shifts += lineStart to INDENT
            changed = true
        }
        if (last) {
            // 最后一段已经整段拷进 out 了，尾段从这里接（否则末尾会被拷第二遍）
            tail = rangeEnd
            break
        }
        // ⚠️ 换行必须自己补回来：循环是按行切的，只 append 行内容会把全文的换行全丢掉
        // （实测：选中三行按 Tab，结果是「  a  b  c」一行）
        out.append('\n')
        lineStart = newline + 1
        // 尾段从这里接着拷：不能直接用 rangeEnd —— 行内的那个 `\n` 已经被上面消费过了，
        // 从 rangeEnd 再拷一次会多出一个换行（实测：选区正好停在行首时出现空行）
        tail = lineStart
    }
    out.append(text, tail, text.length)
    if (!changed) return null

    var newStart = start
    var newEnd = end
    for ((offset, delta) in shifts) {
        if (offset <= start) newStart += delta
        if (!collapsed && offset < end) newEnd += delta
    }
    if (collapsed) newEnd = newStart
    return EditResult(out.toString(), newStart, newEnd)
}
