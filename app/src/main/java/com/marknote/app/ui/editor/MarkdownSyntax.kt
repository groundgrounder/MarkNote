package com.marknote.app.ui.editor

data class Heading(val level: Int, val title: String, val offset: Int)

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

fun fileTitle(name: String): String = name.removeSuffix(".md").removeSuffix(".markdown")

fun parseOutline(text: String): List<Heading> {
    val result = mutableListOf<Heading>()
    val headingRegex = Regex("^(#{1,6})\\s+(.+?)\\s*$")
    var offset = 0

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

private fun fenceOf(line: String): Pair<Char, Int>? {
    val c = line.firstOrNull() ?: return null
    if (c != '`' && c != '~') return null
    val len = line.takeWhile { it == c }.length
    if (len < 3) return null

    if (c == '`' && line.substring(len).contains('`')) return null
    return c to len
}

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

private val inlineMarkdownPattern = Regex("""\[([^\]]*)\]\([^)]*\)|[*_~`]""")

internal fun plainTitle(title: String): String {
    val math = mutableListOf<String>()
    val masked = mathSegmentPattern.replace(title) { m ->

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

private enum class SegmentKind { TEXT, CODE, LINK }

private data class Segment(val kind: SegmentKind, val start: Int, val end: Int)

private val referenceDefinition = Regex("""^ {0,3}\[[^\]]+\]:""")

private val footnoteDefinition = Regex("""^ {0,3}\[\^[^\]]*]:""")

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

private fun scanInline(text: String, lineStart: Int, lineEnd: Int, out: MutableList<Segment>) {
    var i = lineStart
    var textStart = lineStart
    fun flush(end: Int) {
        if (end > textStart) out.add(Segment(SegmentKind.TEXT, textStart, end))
    }
    while (i < lineEnd) {
        val c = text[i]
        if (c == '`') {

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

internal fun keepFootnoteMarkersLiteral(markdown: String): String {
    if (!markdown.contains("[^")) return markdown
    return mapTextSegments(markdown) { it.replace("[^", "\\[^") }
}

private val bareUrlPattern = Regex("""https?://[^\s<>\[\]"'`]+""")

private const val URL_TAIL_TRIM = ".,;:!?\"'*_~"

internal fun autolinkBareUrls(markdown: String): String {
    if (!markdown.contains("http")) return markdown
    return mapTextSegments(markdown) { segment ->
        bareUrlPattern.replace(segment) { m ->
            val url = trimUrlTail(m.value)
            if (url.isEmpty()) m.value else "<$url>" + m.value.substring(url.length)
        }
    }
}

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

internal fun preparePreviewMarkdown(markdown: String): String =
    autolinkBareUrls(keepFootnoteMarkersLiteral(markdown))

internal data class TaskBox(val start: Int, val end: Int, val checked: Boolean)

private val taskBoxPattern = Regex("""(?m)^([ \t]*)\[([ xX])](?=\s|$)""")

internal fun taskBoxRanges(renderedText: String): List<TaskBox> =
    taskBoxPattern.findAll(renderedText).map { m ->
        val start = m.range.first + m.groupValues[1].length
        TaskBox(start, start + 3, m.groupValues[2] != " ")
    }.toList()

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
