package com.marknote.app.ui.editor

internal data class EditResult(val text: String, val selectionStart: Int, val selectionEnd: Int)

private const val INDENT = 2

private val bulletItem = Regex("""^([ \t]*)([-*+])([ \t]+)(\[[ xX]\])([ \t]+)?""")
private val plainBullet = Regex("""^([ \t]*)([-*+])([ \t]+)""")
private val orderedItem = Regex("""^([ \t]*)(\d+)([.)])([ \t]+)""")
private val quoteItem = Regex("""^([ \t]*)(>+)([ \t]*)""")

internal fun continueListOnNewline(text: String, caret: Int): EditResult? {
    if (caret <= 0 || caret > text.length) return null
    val lineBreak = caret - 1
    if (text[lineBreak] != '\n') return null
    val prevStart = text.lastIndexOf('\n', lineBreak - 1) + 1
    val prevLine = text.substring(prevStart, lineBreak)
    if (isInsideCodeFence(text, prevStart)) return null

    val marker = markerFor(prevLine) ?: return null
    if (marker.blank) {

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

private class Marker(val blank: Boolean, val next: String)

private fun markerFor(line: String): Marker? {

    bulletItem.find(line)?.let { m ->

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

internal fun indentLines(text: String, selectionStart: Int, selectionEnd: Int, outdent: Boolean): EditResult? {
    val start = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
    val end = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)
    val collapsed = start == end
    val rangeStart = text.lastIndexOf('\n', start - 1) + 1
    var rangeEnd = if (end > rangeStart && text[end - 1] == '\n') end - 1 else end
    if (rangeEnd < rangeStart) rangeEnd = rangeStart

    val out = StringBuilder(text.length + 16)
    out.append(text, 0, rangeStart)
    val shifts = mutableListOf<Pair<Int, Int>>()
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

            tail = rangeEnd
            break
        }

        out.append('\n')
        lineStart = newline + 1

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
