package com.marknote.app.ui.editor

import android.content.Context
import android.text.Spannable
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.URLSpan
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.core.spans.BulletListItemSpan
import io.noties.markwon.core.spans.OrderedListItemSpan
import io.noties.markwon.ext.latex.JLatexMathNode
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.inlineparser.InlineProcessor
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import org.commonmark.node.Node

@Composable
fun MarkdownPreview(
    markdown: String,
    modifier: Modifier = Modifier,
    textSizeSp: Int = 16,
    docUri: String? = null,
    imageTree: String? = null,
    highlights: List<IntRange> = emptyList(),
    currentHighlight: IntRange? = null,
    scrollTo: PreviewScroll? = null,
    onRenderedText: (String) -> Unit = {},
    onAnchorClick: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val errorColor = MaterialTheme.colorScheme.error.toArgb()

    val markwon = remember(textSizeSp, textColor, errorColor) {
        buildMarkwon(context, textSizeSp, textColor, errorColor)
    }
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val matchColor = MaterialTheme.colorScheme.primaryContainer.toArgb()
    val currentMatchColor = MaterialTheme.colorScheme.primary.toArgb()
    val currentMatchTextColor = MaterialTheme.colorScheme.onPrimary.toArgb()
    val boxOutlineColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val boxFillColor = MaterialTheme.colorScheme.primary.toArgb()
    val boxTickColor = MaterialTheme.colorScheme.onPrimary.toArgb()

    val scrollState = rememberScrollState()
    val holder = remember { PreviewHolder() }

    val preparedMarkdown = remember(markdown, imageTree, docUri) {
        preparePreviewMarkdown(
            rewriteRelativeImages(markdown, imageTree, docDirInTree(docUri, imageTree)),
        )
    }

    AndroidView(
        modifier = modifier.verticalScroll(scrollState),
        factory = { ctx ->
            TextView(ctx).apply {
                setTextColor(textColor)
                setLinkTextColor(linkColor)
                textSize = textSizeSp.toFloat()
                setLineSpacing(0f, 1.35f)

                movementMethod = LinkMovementMethod.getInstance()
                holder.textView = this
            }
        },
        update = { textView ->
            textView.setTextColor(textColor)
            textView.setLinkTextColor(linkColor)
            textView.textSize = textSizeSp.toFloat()

            val rendered = preparedMarkdown
            if (rendered != holder.markdown || holder.markwon !== markwon) {
                holder.markwon = markwon
                holder.markdown = rendered
                holder.spans.clear()
                holder.highlightKey = null

                val spanned = markwon.toMarkdown(rendered)
                decoratePreviewSpanned(
                    spanned = spanned,
                    boxSizePx = textSizeSp * textView.resources.displayMetrics.density * 0.9f,
                    linkColor = linkColor,
                    boxOutlineColor = boxOutlineColor,
                    boxFillColor = boxFillColor,
                    boxTickColor = boxTickColor,
                    onAnchorClick = onAnchorClick,
                )
                markwon.setParsedMarkdown(textView, spanned)
                val plain = textView.text.toString()
                textView.post { onRenderedText(plain) }
            }
            applyHighlights(
                textView = textView,
                holder = holder,
                highlights = highlights,
                current = currentHighlight,
                matchColor = matchColor,
                currentMatchColor = currentMatchColor,
                currentMatchTextColor = currentMatchTextColor,
            )
        },
    )

    LaunchedEffect(scrollTo?.nonce) {
        val request = scrollTo ?: return@LaunchedEffect
        val textView = holder.textView ?: return@LaunchedEffect

        var frames = 0
        while (textView.layout == null && frames < 30) {
            withFrameNanos { }
            frames++
        }
        val layout = textView.layout ?: return@LaunchedEffect
        val offset = request.offset.coerceIn(0, textView.text.length)
        val line = layout.getLineForOffset(offset)

        scrollState.animateScrollTo(layout.getLineTop(line) + textView.totalPaddingTop)
    }
}

data class PreviewScroll(val offset: Int, val nonce: Int)

private class PreviewHolder {
    var textView: TextView? = null
    var markwon: Markwon? = null
    var markdown: String? = null
    val spans = mutableListOf<Any>()
    var highlightKey: HighlightKey? = null
}

private data class HighlightKey(
    val highlights: List<IntRange>,
    val current: IntRange?,
    val matchColor: Int,
    val currentMatchColor: Int,
    val currentMatchTextColor: Int,
)

private fun applyHighlights(
    textView: TextView,
    holder: PreviewHolder,
    highlights: List<IntRange>,
    current: IntRange?,
    matchColor: Int,
    currentMatchColor: Int,
    currentMatchTextColor: Int,
) {
    val key = HighlightKey(
        highlights = highlights,
        current = current,
        matchColor = matchColor,
        currentMatchColor = currentMatchColor,
        currentMatchTextColor = currentMatchTextColor,
    )
    if (key == holder.highlightKey) return
    holder.highlightKey = key

    if (highlights.isEmpty() && holder.spans.isEmpty()) return
    val text = textView.text as? Spannable ?: return
    holder.spans.forEach { text.removeSpan(it) }
    holder.spans.clear()
    highlights.forEach { range ->
        val start = range.first.coerceIn(0, text.length)
        val end = (range.last + 1).coerceIn(start, text.length)
        if (start >= end) return@forEach
        val isCurrent = current != null && current.first == range.first
        val background = BackgroundColorSpan(if (isCurrent) currentMatchColor else matchColor)
        text.setSpan(background, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        holder.spans += background
        if (isCurrent) {
            val foreground = ForegroundColorSpan(currentMatchTextColor)
            text.setSpan(foreground, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            holder.spans += foreground
        }
    }
    textView.invalidate()
}

private fun decoratePreviewSpanned(
    spanned: Spanned,
    boxSizePx: Float,
    linkColor: Int,
    boxOutlineColor: Int,
    boxFillColor: Int,
    boxTickColor: Int,
    onAnchorClick: (String) -> Unit,
) {
    val spannable = spanned as? Spannable ?: return

    val listSpans = buildList<Any> {
        addAll(spannable.getSpans(0, spannable.length, BulletListItemSpan::class.java))
        addAll(spannable.getSpans(0, spannable.length, OrderedListItemSpan::class.java))
    }
    taskBoxRanges(spanned.toString())
        .filter { box ->
            listSpans.any { span ->
                spannable.getSpanStart(span) <= box.start && box.end <= spannable.getSpanEnd(span)
            }
        }
        .forEach { box ->
            spannable.setSpan(
                TaskBoxSpan(boxSizePx, box.checked, boxOutlineColor, boxFillColor, boxTickColor),
                box.start,
                box.end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }

    spannable.getSpans(0, spannable.length, URLSpan::class.java).forEach { span ->
        val url = span.url ?: return@forEach
        if (!url.startsWith("#")) return@forEach
        val start = spannable.getSpanStart(span)
        val end = spannable.getSpanEnd(span)
        val flags = spannable.getSpanFlags(span)
        spannable.removeSpan(span)
        spannable.setSpan(AnchorLinkSpan(url, linkColor, onAnchorClick), start, end, flags)
    }
}

private fun buildMarkwon(context: Context, textSizeSp: Int, textColor: Int, errorColor: Int): Markwon {
    val appContext = context.applicationContext

    val mathTextPx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        textSizeSp.toFloat(),
        context.resources.displayMetrics,
    )
    return Markwon.builder(context)
        .usePlugin(StrikethroughPlugin.create())
        .usePlugin(TablePlugin.create(context))

        .usePlugin(
            MarkwonInlineParserPlugin.create { builder ->
                builder.addInlineProcessor(DollarMathInlineProcessor())
            },
        )
        .usePlugin(
            JLatexMathPlugin.create(mathTextPx) { plugin ->

                plugin.inlinesEnabled(true)

                plugin.theme()
                    .textColor(textColor)
                    .inlineTextColor(textColor)
                    .blockTextColor(textColor)

                plugin.errorHandler { rawLatex, error ->

                    val latex = rawLatex.trim()

                    //   adb logcat -d | grep "Error displaying latex" | sed 's/.*E JLatexMathPlugin: //' | sort -u
                    val onOneLine = latex.replace("\n", "\\n")
                    android.util.Log.e("JLatexMathPlugin", "Error displaying latex: `$onOneLine`", error)
                    latexErrorDrawable(appContext, errorColor, textSizeSp, latex)
                }
            },
        )
        .usePlugin(ImagesPlugin.create { plugin ->
            plugin.addSchemeHandler(LocalImageSchemeHandler(appContext))

            plugin.errorHandler { _, error -> imageErrorDrawable(appContext, errorColor, error) }
        })
        .build()
}

private class DollarMathInlineProcessor : InlineProcessor() {

    override fun specialCharacter(): Char = '$'

    override fun parse(): Node? {

        val match = doubleDollarMathPattern.matchAt(input, index)
            ?: inlineMathPattern.matchAt(input, index)
            ?: return consumeLoneDollar()
        index = match.range.last + 1

        return JLatexMathNode().apply { latex(match.groupValues[1]) }
    }

    private fun consumeLoneDollar(): Node {
        index += 1
        return text("$")
    }
}
