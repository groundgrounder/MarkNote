package com.marknote.app.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.DocumentsContract
import android.text.Spannable
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.Base64
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
import androidx.documentfile.provider.DocumentFile
import com.marknote.app.R
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
 * - 相对路径（如 ![](img/a.png)）按**文档所在目录**解析（与 Markdown 惯例一致），`..` 可正常上行；
 *   解析结果必须落在用户授权的目录树内，落在外面（或找不到）时显示可见的错误占位
 * - 文档不在授权树内时，退回「相对授权根目录」解析（兼容早期写法）
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
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val errorColor = MaterialTheme.colorScheme.error.toArgb()
    // 加载失败的占位图要用主题的 error 色，主题切换后重建
    val markwon = remember(errorColor) { buildMarkwon(context, errorColor) }
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
            val rendered = rewriteRelativeImages(markdown, imageTree, docDirInTree(docUri, imageTree))
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

private fun buildMarkwon(context: Context, errorColor: Int): Markwon {
    val appContext = context.applicationContext
    return Markwon.builder(context)
        .usePlugin(StrikethroughPlugin.create())
        .usePlugin(TablePlugin.create(context))
        .usePlugin(ImagesPlugin.create { plugin ->
            plugin.addSchemeHandler(LocalImageSchemeHandler(appContext))
            // 必须显式注册：不注册时 Markwon 只把图片回落到 alt 文本（空 alt 就什么都不显示），
            // 用户只看到一个破图小方块，拿不到任何线索
            plugin.errorHandler { _, error -> imageErrorDrawable(appContext, errorColor, error) }
        })
        .build()
}

/** 加载失败时顶替图片的占位：一个描边框 + 失败原因（单行，超出宽度省略） */
private fun imageErrorDrawable(context: Context, color: Int, error: Throwable): Drawable {
    val metrics = context.resources.displayMetrics
    // 用 TypedValue 换算（scaledDensity 已废弃），顺带尊重用户的字体缩放
    fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, metrics)
    fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, metrics)

    val reason = error.message?.takeIf { it.isNotBlank() }
        ?: context.getString(R.string.image_error_generic)
    val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = sp(12f)
    }
    val pad = dp(12f)
    val maxWidth = dp(260f)
    val shown = TextUtils.ellipsize(
        reason,
        textPaint,
        maxWidth - pad * 2,
        TextUtils.TruncateAt.END,
    ).toString()
    val width = (textPaint.measureText(shown) + pad * 2).toInt().coerceIn(dp(120f).toInt(), maxWidth.toInt())
    val height = (textPaint.textSize * 2.4f).toInt()
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val inset = dp(1f) / 2
    val radius = dp(6f)
    val left = inset
    val top = inset
    val right = width - inset
    val bottom = height - inset
    canvas.drawRoundRect(
        left, top, right, bottom, radius, radius,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; alpha = 0x1A },
    )
    canvas.drawRoundRect(
        left, top, right, bottom, radius, radius,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = dp(1f)
        },
    )
    val baseline = height / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
    canvas.drawText(shown, pad, baseline, textPaint)
    return BitmapDrawable(context.resources, bitmap)
}

/**
 * 文档在授权目录树内的相对目录（如 "notes/img"，就在树根时为 ""）。
 *
 * 相对路径图片要按「文档所在目录」解析才符合 Markdown 惯例，所以改写时得把它带上。
 * 文档与授权目录不在同一个 provider、或文档根本不在授权树内时返回 ""，
 * 由调用方退回「相对授权根目录」的老行为。
 */
internal fun docDirInTree(docUri: String?, treeUri: String?): String {
    if (docUri.isNullOrEmpty() || treeUri.isNullOrEmpty()) return ""
    val doc = runCatching { Uri.parse(docUri) }.getOrNull() ?: return ""
    val tree = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return ""
    if (doc.authority != tree.authority) return ""
    val docId = runCatching { DocumentsContract.getDocumentId(doc) }.getOrNull() ?: return ""
    val treeId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return ""
    val prefix = treeId.trimEnd('/') + "/"
    if (!docId.startsWith(prefix)) return ""
    return docId.substring(prefix.length).substringBeforeLast('/', "")
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
 * 把无 scheme 的图片相对路径改写为 marknote-rel://img/<tree>/<path…>?base=<文档所在目录>。
 *
 * `..` 必须原样留到 handler 里再结算——这里既不知道授权树里有什么，也不能做 I/O。
 * 早期实现直接把它过滤掉，`../a.png` 会静默变成授权根下的 `a.png`：
 * 找不到只是显示不出来，而那个位置**恰好有同名文件时会静默加载错的图**。
 *
 * 未授权图片文件夹（imageTree == null）时原样保留，预览中显示占位与 alt 文本。
 */
private fun rewriteRelativeImages(markdown: String, imageTree: String?, docDir: String): String {
    if (imageTree == null || !markdown.contains("![")) return markdown
    return imagePattern.replace(markdown) { m ->
        val path = m.groupValues[2]
        if (hasScheme(path)) m.value
        else {
            val builder = Uri.Builder().scheme("marknote-rel").authority("img").appendPath(imageTree)
            path.split('/').filter { it.isNotEmpty() && it != "." }.forEach { builder.appendPath(it) }
            if (docDir.isNotEmpty()) builder.appendQueryParameter("base", docDir)
            "![${m.groupValues[1]}]($builder${m.groupValues[3]})"
        }
    }
}

// ---------- 图片加载 ----------

/** content:// / file:// / data: / marknote-rel:// 四种来源的图片加载 */
private class LocalImageSchemeHandler(private val context: Context) : SchemeHandler() {

    /**
     * 注意：这里抛出的异常文案会经 ErrorHandler 显示在图片位置上，属于用户可见文案，
     * 必须走资源文件（context 是 applicationContext，语言跟随应用设置）。
     * 连 openRelative() 里那条「找不到」也一并走资源——它现在同样会显示给用户。
     */
    override fun handle(raw: String, uri: Uri): ImageItem {
        if (uri.scheme == "data") return decodeDataUri(raw)
        // 出错文案会显示在图片位置，而 marknote-rel 的 path 是整条 tree URI，读起来没有意义，
        // 换成能和文档里那行对上的相对路径
        val target = if (uri.scheme == "marknote-rel") {
            (uri.getQueryParameter("base").orEmpty().split('/') + uri.pathSegments.drop(1))
                .filter { it.isNotEmpty() }
                .joinToString("/")
        } else {
            uri.path ?: uri.toString()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // 注意：inJustDecodeBounds 模式下 decodeStream 返回 null 是正常的，不能用它判断成败
        val s1 = open(uri) ?: throw IOException(context.getString(R.string.image_open_failed, target))
        s1.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException(context.getString(R.string.image_decode_failed, target))
        }
        // 大图降采样，防 OOM
        var sample = 1
        while (bounds.outWidth / sample > 4096 || bounds.outHeight / sample > 4096) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val s2 = open(uri) ?: throw IOException(context.getString(R.string.image_open_failed, target))
        val bitmap = s2.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IOException(context.getString(R.string.image_decode_failed, target))
        return ImageItem.withResult(BitmapDrawable(context.resources, bitmap))
    }

    override fun supportedSchemes(): Collection<String> =
        listOf("content", "file", "data", "marknote-rel")

    private fun open(uri: Uri): InputStream? = when (uri.scheme) {
        "file" -> uri.path?.let { FileInputStream(it) }
        "marknote-rel" -> openRelative(uri)
        else -> runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
    }

    /**
     * 沿授权的目录树查找相对路径指向的图片文件。
     *
     * 路径按「文档所在目录（uri 的 base 参数）＋ 相对路径」结算，`..` 正常上行；
     * 结算结果越出授权根、或树里没有这个文件，就判定失败。文档不在授权树内时
     * base 为空，此时再退回「相对授权根目录」解析一次，兼容早期写法。
     *
     * 失败时**必须把异常抛出去**（getOrThrow）：ErrorHandler 会把它显示在图片位置上。
     * 早期这里 getOrNull() 吞掉异常，用户只看到一个破图小方块，没有任何线索。
     */
    private fun openRelative(uri: Uri): InputStream? = runCatching {
        val segments = uri.pathSegments ?: return null
        if (segments.isEmpty()) return null
        val treeUri = Uri.parse(segments[0])
        val rel = segments.drop(1)
        if (rel.isEmpty()) throw IOException(context.getString(R.string.image_error_generic))
        val base = uri.getQueryParameter("base").orEmpty()
        resolve(treeUri, base, rel)
            ?: (if (base.isNotEmpty()) resolve(treeUri, "", rel) else null)
            ?: throw IOException(
                context.getString(
                    R.string.image_not_found,
                    (base.split('/') + rel).filter { it.isNotEmpty() }.joinToString("/"),
                ),
            )
    }.onFailure {
        android.util.Log.w("MarkNote-img", "openRelative failed: $uri", it)
    }.getOrThrow()

    /**
     * 把 base 与相对路径结算成树内路径后逐级 findFile。
     * 路径越出树根、目录不存在、文件不存在都返回 null（具体原因由调用方统一成一条用户文案）。
     */
    private fun resolve(treeUri: Uri, base: String, rel: List<String>): InputStream? {
        val stack = ArrayDeque<String>()
        base.split('/').filter { it.isNotEmpty() }.forEach { stack.addLast(it) }
        for (seg in rel) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (stack.isEmpty()) return null else stack.removeLast()
                else -> stack.addLast(seg)
            }
        }
        if (stack.isEmpty()) return null
        var dir = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        for (name in stack.dropLast(1)) {
            dir = dir.findFile(name)?.takeIf { it.isDirectory } ?: return null
        }
        val file = dir.findFile(stack.last())?.takeIf { it.isFile } ?: return null
        return context.contentResolver.openInputStream(file.uri)
    }

    /** data:image/png;base64,.... 内嵌图 */
    private fun decodeDataUri(raw: String): ImageItem {
        val comma = raw.indexOf(',')
        if (comma < 0) throw IOException(context.getString(R.string.image_invalid_data))
        val bytes = Base64.decode(raw.substring(comma + 1), Base64.DEFAULT)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IOException(context.getString(R.string.image_decode_data_failed))
        return ImageItem.withResult(BitmapDrawable(context.resources, bitmap))
    }
}
