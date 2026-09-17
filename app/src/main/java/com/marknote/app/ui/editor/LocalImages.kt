package com.marknote.app.ui.editor

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import com.marknote.app.R
import io.noties.markwon.image.ImageItem
import io.noties.markwon.image.SchemeHandler
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * 预览里的图片：相对路径改写 + 在授权目录树里找文件。
 *
 * 2026-09-16 从 MarkdownPreview.kt 拆出来。这里全是 **I/O 与路径结算**，与 Compose 无关：
 * 混在 UI 文件里既看不清边界，也没法单独验证。MarkdownPreview.kt 只负责把它接进 Markwon。
 *
 * 支持的来源：
 * - 相对路径（如 ![](img/a.png)）按**文档所在目录**解析（与 Markdown 惯例一致），`..` 可正常上行；
 *   解析结果必须落在用户授权的目录树内，落在外面（或找不到）时显示可见的错误占位
 * - 文档不在授权树内时，退回「相对授权根目录」解析（兼容早期写法）
 * - content:// / file:// 直接读（需已有权限）
 * - data:image/...;base64,... 内嵌图直接解码，无需任何权限
 */

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
internal fun rewriteRelativeImages(markdown: String, imageTree: String?, docDir: String): String {
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

/** content:// / file:// / data: / marknote-rel:// 四种来源的图片加载 */
internal class LocalImageSchemeHandler(private val context: Context) : SchemeHandler() {

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
