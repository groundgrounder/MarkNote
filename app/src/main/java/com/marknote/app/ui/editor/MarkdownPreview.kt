package com.marknote.app.ui.editor

import android.content.Context
import android.text.Spannable
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
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
import io.noties.markwon.ext.latex.JLatexMathNode
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.inlineparser.InlineProcessor
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import org.commonmark.node.Node

/**
 * Markdown 预览：Markwon（View 体系）经 AndroidView 嵌入 Compose。
 * TextView 在竖向滚动容器内按内容高度展开，由 Compose 处理滚动。
 *
 * 本文件只负责**把 Markwon 接进 Compose**：实例装配、渲染、搜索高亮叠加、滚动定位。
 * 相对路径图片的改写与加载在 LocalImages.kt（纯 I/O），两种失败占位的绘制在
 * PreviewPlaceholders.kt（2026-09-16 从本文件拆出，原先 571 行混了四层职责）。
 *
 * 只读预览下的搜索/大纲：
 * - 搜索是按**渲染结果**做的（高亮命中的也是渲染后的文字），所以两套操作与编辑器一致
 * - onRenderedText 把渲染出的纯文本回传给调用方，调用方用它算命中位置
 * - highlights / currentHighlight 是渲染文本里的偏移区间，scrollTo 用来滚动定位
 */
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
) {
    val context = LocalContext.current
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val errorColor = MaterialTheme.colorScheme.error.toArgb()
    // 失败占位图的颜色、公式的尺寸与颜色都是**烤进** Markwon 插件配置的（配置在 build 时就固定了），
    // 所以这三样变了必须重建实例；重建后还要重渲，见 update 里对 holder.markwon 的比对
    val markwon = remember(textSizeSp, textColor, errorColor) {
        buildMarkwon(context, textSizeSp, textColor, errorColor)
    }
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val matchColor = MaterialTheme.colorScheme.primaryContainer.toArgb()
    val currentMatchColor = MaterialTheme.colorScheme.primary.toArgb()
    val currentMatchTextColor = MaterialTheme.colorScheme.onPrimary.toArgb()

    val scrollState = rememberScrollState()
    val holder = remember { PreviewHolder() }

    AndroidView(
        modifier = modifier.verticalScroll(scrollState),
        factory = { ctx ->
            TextView(ctx).apply {
                setTextColor(textColor)
                setLinkTextColor(linkColor)
                textSize = textSizeSp.toFloat()
                setLineSpacing(0f, 1.35f)
                // 链接可点击跳转（LinkMovementMethod 与文本选择互斥，预览里链接交互优先）
                movementMethod = LinkMovementMethod.getInstance()
                holder.textView = this
            }
        },
        update = { textView ->
            textView.setTextColor(textColor)
            textView.setLinkTextColor(linkColor)
            textView.textSize = textSizeSp.toFloat()
            // 内容没变就不要重设文本：Markwon.setMarkdown 会重建 Spannable，
            // 既会丢掉搜索高亮，也会把滚动位置弹回顶部。
            // 但 Markwon 实例换了（预览字号或主题色变了）必须重渲 —— 公式的尺寸与颜色、
            // 占位图的颜色都是建实例时定死的，只改 TextView 的 textSize 它们不会跟着变
            val rendered = rewriteRelativeImages(markdown, imageTree, docDirInTree(docUri, imageTree))
            if (rendered != holder.markdown || holder.markwon !== markwon) {
                holder.markwon = markwon
                holder.markdown = rendered
                holder.spans.clear()
                markwon.setMarkdown(textView, rendered)
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

    // 定位（大纲跳转 / 搜索命中）：把目标行滚到视口顶部
    LaunchedEffect(scrollTo?.nonce) {
        val request = scrollTo ?: return@LaunchedEffect
        val textView = holder.textView ?: return@LaunchedEffect
        // 首次渲染时 layout 还没生成，等几帧
        var frames = 0
        while (textView.layout == null && frames < 30) {
            withFrameNanos { }
            frames++
        }
        val layout = textView.layout ?: return@LaunchedEffect
        val offset = request.offset.coerceIn(0, textView.text.length)
        val line = layout.getLineForOffset(offset)
        // 目标行超出滚动上限时 animateScrollTo 会夹到 maxValue（滚到底）——
        // 文末那几个标题本来就顶不到屏幕顶部，这是预期行为，不是算错了偏移
        scrollState.animateScrollTo(layout.getLineTop(line) + textView.totalPaddingTop)
    }
}

/**
 * 预览定位请求：把渲染结果滚动到 offset 所在的行。
 * 同一个 offset 可能被重复请求（例如关掉搜索再点同一项），用 nonce 区分是否需要重新滚动。
 */
data class PreviewScroll(val offset: Int, val nonce: Int)

/** 预览渲染状态：TextView 引用、上次渲染用的 Markwon、已渲染的 Markdown、当前施加的高亮 span */
private class PreviewHolder {
    var textView: TextView? = null
    var markwon: Markwon? = null
    var markdown: String? = null
    val spans = mutableListOf<Any>()
}

/**
 * 在渲染结果上叠加搜索高亮。只增删 span、不替换文本——
 * 替换文本会重建 Spannable 并把滚动位置弹回顶部。
 */
private fun applyHighlights(
    textView: TextView,
    holder: PreviewHolder,
    highlights: List<IntRange>,
    current: IntRange?,
    matchColor: Int,
    currentMatchColor: Int,
    currentMatchTextColor: Int,
) {
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

private fun buildMarkwon(context: Context, textSizeSp: Int, textColor: Int, errorColor: Int): Markwon {
    val appContext = context.applicationContext
    // JLaTeXMath 的 textSize 是**像素**：它直接拿去 setSize 算位图尺寸，中间没有任何密度换算。
    // 而 TextView 的 textSize 走的是 sp，所以这里必须自己换算 —— 直接把 sp 传进去公式会小一圈
    val mathTextPx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        textSizeSp.toFloat(),
        context.resources.displayMetrics,
    )
    return Markwon.builder(context)
        .usePlugin(StrikethroughPlugin.create())
        .usePlugin(TablePlugin.create(context))
        // 行内公式装在 MarkwonInlineParser 上：ext-latex 的 configure() 里 require 了这个插件，
        // 不装会在 build() 处直接抛。代价是它会接管**整个**行内解析（emphasis / code / link /
        // autolink 都换成 Markwon 的实现）—— 回归风险来自这里，不是来自公式本身。
        // 这里额外注册自己的 `$` 处理器，单 `$…$` 与 `$$…$$` 都归它管
        .usePlugin(
            MarkwonInlineParserPlugin.create { builder ->
                builder.addInlineProcessor(DollarMathInlineProcessor())
            },
        )
        .usePlugin(
            JLatexMathPlugin.create(mathTextPx) { plugin ->
                // 行内公式默认是**关**的：Builder 里只有 blocksEnabled 被初始化成 true，
                // inlinesEnabled 保持 boolean 的默认值 false，不显式打开就只能写块级公式
                plugin.inlinesEnabled(true)
                // 公式颜色必须跟随主题：不给就是 JLaTeXMath 的默认黑色，深色主题下黑字黑底
                plugin.theme()
                    .textColor(textColor)
                    .inlineTextColor(textColor)
                    .blockTextColor(textColor)
                // 公式写错时的可见占位。不接的话默认只写一条 logcat，
                // 界面上虽然还留着公式源，但没有任何「这里出错了」的信号 —— 与图片失败的处理也不一致
                plugin.errorHandler { rawLatex, error ->
                    // 块级公式的 destination 带首尾换行，trim 掉：否则占位里公式源前后会多出空白
                    val latex = rawLatex.trim()
                    // ⚠️ 接了 errorHandler 之后，ext-latex 自己那条
                    // `Error displaying latex: \`…\`` 就**不再写了** —— 看它的字节码，
                    // Log.e 只出现在 errorHandler == null 的那个分支里。
                    // 所以这里自己补一条同 tag、同格式的日志，保住「grep 一次列全本文档
                    // 所有渲染失败的公式」这个排查手段；换行压成字面 \n 以保持一条日志一行：
                    //   adb logcat -d | grep "Error displaying latex" | sed 's/.*E JLatexMathPlugin: //' | sort -u
                    val onOneLine = latex.replace("\n", "\\n")
                    android.util.Log.e("JLatexMathPlugin", "Error displaying latex: `$onOneLine`", error)
                    latexErrorDrawable(appContext, errorColor, textSizeSp, latex)
                }
            },
        )
        .usePlugin(ImagesPlugin.create { plugin ->
            plugin.addSchemeHandler(LocalImageSchemeHandler(appContext))
            // 必须显式注册：不注册时 Markwon 只把图片回落到 alt 文本（空 alt 就什么都不显示），
            // 用户只看到一个破图小方块，拿不到任何线索
            plugin.errorHandler { _, error -> imageErrorDrawable(appContext, errorColor, error) }
        })
        .build()
}

/**
 * 行内公式的分隔符处理：单 `$…$`（Pandoc 边界规则见 [inlineMathPattern]）与 `$$…$$`。
 *
 * 为什么连 `$$` 也由自己接管：ext-latex 自带的那个处理器内部是 `match(RE)` → `Matcher.find()`，
 * 而 find() **允许跳过前面的字符**。于是轮到「孤立的 `$`」时
 * （例如 `价格 $5，$$E=mc^2$$`），它会一路往后找到那个 `$$…$$`、把 index 直接推到公式末尾——
 * 中间的 `$5，` 就跟着被吞掉了。多个同字符处理器的尝试顺序由注册顺序决定，
 * 靠「谁先谁后」规避并不可靠，所以这里让本处理器对 `$` **永不返回 null**：
 * 认得出公式就造节点，认不出就消费掉这一个字符。这样 ext-latex 那个处理器永远轮不到 `$`，
 * 越位问题从根上不存在。
 *
 * 不能改用 `plugin.inlinesEnabled(false)` 关掉它：同一个开关也门控着 addInlineVisitor，
 * 关掉之后这里造出的 JLatexMathNode 就没有 visitor 去渲染了（公式整体消失）。
 */
private class DollarMathInlineProcessor : InlineProcessor() {

    override fun specialCharacter(): Char = '$'

    override fun parse(): Node? {
        // 两个 pattern 都用 matchAt：必须**从当前位置开始**匹配，绝不许往后跳。
        // 两个都失败时返回 null 是安全的——框架会把 index 回滚（见 MarkwonInlineParser.parseInline）
        val match = doubleDollarMathPattern.matchAt(input, index)
            ?: inlineMathPattern.matchAt(input, index)
            ?: return consumeLoneDollar()
        index = match.range.last + 1
        // 节点只带公式源（分隔符已由 pattern 排除在捕获组外），与 ext-latex 自带处理器的行为一致
        return JLatexMathNode().apply { latex(match.groupValues[1]) }
    }

    /** 认不出的 `$` 原样输出——但必须自己把这一位消费掉，不给后面的处理器留越位的机会 */
    private fun consumeLoneDollar(): Node {
        index += 1
        return text("$")
    }
}
