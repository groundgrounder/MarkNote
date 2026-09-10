package com.marknote.app.ui.editor

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.text.method.LinkMovementMethod
import android.util.Base64
import android.widget.TextView
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
 * Markdown 预览：Markwon（View 体系）经 AndroidView 嵌入 Compose。
 * TextView 在竖向滚动容器内按内容高度展开，由 Compose 处理滚动。
 *
 * 图片：
 * - 相对路径（如 ![](img/a.png)）需要用户先授权文档所在文件夹，
 *   渲染前改写为自定义 marknote-rel:// scheme，由 handler 沿授权目录树查找
 * - content:// / file:// 直接读（需已有权限）
 * - data:image/...;base64,... 内嵌图直接解码，无需任何权限
 */
@Composable
fun MarkdownPreview(
    markdown: String,
    modifier: Modifier = Modifier,
    textSizeSp: Int = 16,
    docUri: String? = null,
    imageTree: String? = null,
) {
    val context = LocalContext.current
    val markwon = remember { buildMarkwon(context) }
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()

    AndroidView(
        modifier = modifier.verticalScroll(rememberScrollState()),
        factory = { ctx ->
            TextView(ctx).apply {
                setTextColor(textColor)
                setLinkTextColor(linkColor)
                textSize = textSizeSp.toFloat()
                setLineSpacing(0f, 1.35f)
                // 链接可点击跳转（LinkMovementMethod 与文本选择互斥，预览里链接交互优先）
                movementMethod = LinkMovementMethod.getInstance()
            }
        },
        update = { textView ->
            textView.setTextColor(textColor)
            textView.textSize = textSizeSp.toFloat()
            markwon.setMarkdown(textView, rewriteRelativeImages(markdown, imageTree))
        },
    )
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
