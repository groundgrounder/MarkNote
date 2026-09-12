package com.marknote.app.ui.editor

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.text.Spannable
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.Base64
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
import androidx.documentfile.provider.DocumentFile
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.image.ImageItem
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.image.SchemeHandler
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * 预览定位请求：把渲染结果滚动到 offset 所在的行。
 * 同一个 offset 可能被重复请求（例如关掉搜索再点同一项），用 nonce 区分是否需要重新滚动。
 */
data class PreviewScroll(val offset: Int, val nonce: Int)

/**
 * Markdown 预览：Markwon（View 体系）经 AndroidView 嵌入 Compose。
 * TextView 在竖向滚动容器内按内容高度展开，由 Compose 处理滚动。
 *
 * 图片：
 * - 相对路径（如 ![](img/a.png)）需要用户先授权文档所在文件夹，
 *   渲染前改写为自定义 marknote-rel:// scheme，由 handler 沿授权目录树查找
 * - content:// / file:// 直接读（需已有权限）
 * - data:image/...;base64,... 内嵌图直接解码，无需任何权限
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
    val markwon = remember { buildMarkwon(context) }
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
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
            // 既会丢掉搜索高亮，也会把滚动位置弹回顶部
            val rendered = rewriteRelativeImages(markdown, imageTree)
            if (rendered != holder.markdown) {
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
        scrollState.animateScrollTo(layout.getLineTop(line) + textView.totalPaddingTop)
    }
}

/** 预览渲染状态：TextView 引用、已渲染的 Markdown、当前施加的高亮 span */
private class PreviewHolder {
    var textView: TextView? = null
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

private fun buildMarkwon(context: Context): Markwon {
    val appContext = context.applicationContext
    return Markwon.builder(context)
        .usePlugin(StrikethroughPlugin.create())
        .usePlugin(TablePlugin.create(context))
        .usePlugin(ImagesPlugin.create { plugin ->
            plugin.addSchemeHandler(LocalImageSchemeHandler(appContext))
        })
        .build()
}

// ---------- 相对路径图片 ----------

private val imagePattern = Regex("""!\[([^\]]*)\]\(([^)\s]+)(\s+"[^"]*")?\)""")

/** 已带 scheme（http:、content:、file:、data: 等）就不再处理 */
private fun hasScheme(url: String): Boolean =
    url.matches(Regex("^[A-Za-z][A-Za-z0-9+.-]*:.*"))

/** 文本里是否存在相对路径图片（决定预览页是否显示授权引导条） */
internal fun hasRelativeImage(markdown: String): Boolean =
    markdown.contains("![") && imagePattern.findAll(markdown).any { !hasScheme(it.groupValues[2]) }

/**
 * 把无 scheme 的图片相对路径改写为 marknote-rel://img/<tree>/<path…>。
 * 未授权图片文件夹（imageTree == null）时原样保留，预览中显示占位与 alt 文本。
 */
private fun rewriteRelativeImages(markdown: String, imageTree: String?): String {
    if (imageTree == null || !markdown.contains("![")) return markdown
    return imagePattern.replace(markdown) { m ->
        val path = m.groupValues[2]
        if (hasScheme(path)) m.value
        else {
            val builder = Uri.Builder().scheme("marknote-rel").authority("img").appendPath(imageTree)
            path.split('/').filter { it.isNotEmpty() && it != "." }.forEach { builder.appendPath(it) }
            "![${m.groupValues[1]}]($builder${m.groupValues[3]})"
        }
    }
}

// ---------- 图片加载 ----------

/** content:// / file:// / data: / marknote-rel:// 四种来源的图片加载 */
private class LocalImageSchemeHandler(private val context: Context) : SchemeHandler() {

    override fun handle(raw: String, uri: Uri): ImageItem {
        if (uri.scheme == "data") return decodeDataUri(raw)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // 注意：inJustDecodeBounds 模式下 decodeStream 返回 null 是正常的，不能用它判断成败
        val s1 = open(uri) ?: throw IOException("无法打开图片: ${uri.path ?: uri}")
        s1.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("无法解码图片: ${uri.path ?: uri}")
        }
        // 大图降采样，防 OOM
        var sample = 1
        while (bounds.outWidth / sample > 4096 || bounds.outHeight / sample > 4096) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val s2 = open(uri) ?: throw IOException("无法打开图片: ${uri.path ?: uri}")
        val bitmap = s2.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IOException("无法解码图片: ${uri.path ?: uri}")
        return ImageItem.withResult(BitmapDrawable(context.resources, bitmap))
    }

    override fun supportedSchemes(): Collection<String> =
        listOf("content", "file", "data", "marknote-rel")

    private fun open(uri: Uri): InputStream? = when (uri.scheme) {
        "file" -> uri.path?.let { FileInputStream(it) }
        "marknote-rel" -> openRelative(uri)
        else -> runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
    }

    /** 沿授权的目录树逐段查找相对路径指向的图片文件 */
    private fun openRelative(uri: Uri): InputStream? = runCatching {
        val segments = uri.pathSegments ?: return null
        if (segments.isEmpty()) return null
        val treeUri = Uri.parse(segments[0])
        var dir = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IOException("fromTreeUri 失败: $treeUri")
        val parts = segments.drop(1).filter { it != "." && it != ".." }
        for (name in parts.dropLast(1)) {
            dir = dir.findFile(name)?.takeIf { it.isDirectory }
                ?: throw IOException("目录不存在: $name")
        }
        val file = dir.findFile(parts.last())?.takeIf { it.isFile }
            ?: throw IOException("文件不存在: ${parts.last()}")
        context.contentResolver.openInputStream(file.uri)
            ?: throw IOException("openInputStream 返回 null")
    }.onFailure {
        android.util.Log.w("MarkNote-img", "openRelative failed: $uri", it)
    }.getOrNull()

    /** data:image/png;base64,.... 内嵌图 */
    private fun decodeDataUri(raw: String): ImageItem {
        val comma = raw.indexOf(',')
        if (comma < 0) throw IOException("无效的 data 图片")
        val bytes = Base64.decode(raw.substring(comma + 1), Base64.DEFAULT)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IOException("无法解码 data 图片")
        return ImageItem.withResult(BitmapDrawable(context.resources, bitmap))
    }
}
