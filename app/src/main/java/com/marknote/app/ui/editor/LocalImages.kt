package com.marknote.app.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.media.ExifInterface
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import com.marknote.app.R
import io.noties.markwon.image.ImageItem
import io.noties.markwon.image.SchemeHandler
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

private val imagePattern = Regex("""!\[([^\]]*)\]\(([^)\s]+)(\s+"[^"]*")?\)""")
private val schemePattern = Regex("^[A-Za-z][A-Za-z0-9+.-]*:.*")

private fun hasScheme(url: String): Boolean = schemePattern.matches(url)

internal fun hasRelativeImage(markdown: String): Boolean =
    markdown.contains("![") && imagePattern.findAll(markdown).any { !hasScheme(it.groupValues[2]) }

internal fun rewriteRelativeImages(markdown: String, imageTree: String?, docDir: String): String {
    if (imageTree == null || !markdown.contains("![")) return markdown
    return imagePattern.replace(markdown) { m ->
        val path = m.groupValues[2]
        if (hasScheme(path)) {
            m.value
        } else {
            val builder = Uri.Builder().scheme("marknote-rel").authority("img").appendPath(imageTree)
            path.split('/').filter { it.isNotEmpty() && it != "." }.forEach { builder.appendPath(it) }
            if (docDir.isNotEmpty()) builder.appendQueryParameter("base", docDir)
            "![${m.groupValues[1]}]($builder${m.groupValues[3]})"
        }
    }
}

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

internal class LocalImageSchemeHandler(private val context: Context) : SchemeHandler() {

    private val maxImageDimension: Int
        get() {
            val dm = context.resources.displayMetrics
            return minOf(MAX_BITMAP_DIM, maxOf(dm.widthPixels, dm.heightPixels).coerceAtLeast(1080))
        }

    override fun handle(raw: String, uri: Uri): ImageItem {
        if (uri.scheme == "data") return decodeDataUri(raw)

        val target = if (uri.scheme == "marknote-rel") {
            (uri.getQueryParameter("base").orEmpty().split('/') + uri.pathSegments.drop(1))
                .filter { it.isNotEmpty() }
                .joinToString("/")
        } else {
            uri.path ?: uri.toString()
        }
        val resolvedUri = if (uri.scheme == "marknote-rel") resolveRelativeUri(uri) else uri
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val s1 = openResolved(resolvedUri)
            ?: throw IOException(context.getString(R.string.image_open_failed, target))
        s1.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException(context.getString(R.string.image_decode_failed, target))
        }

        val sample = computeSampleSize(bounds.outWidth, bounds.outHeight, maxImageDimension)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val s2 = openResolved(resolvedUri)
            ?: throw IOException(context.getString(R.string.image_open_failed, target))
        val decoded = s2.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IOException(context.getString(R.string.image_decode_failed, target))

        val orientation = if (mayHaveExif(bounds.outMimeType)) {
            runCatching {
                openResolved(resolvedUri)?.use {
                    ExifInterface(it).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
                }
            }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        } else {
            ExifInterface.ORIENTATION_NORMAL
        }

        val bitmap = applyExifOrientation(decoded, orientation)
        return ImageItem.withResult(BitmapDrawable(context.resources, bitmap))
    }

    override fun supportedSchemes(): Collection<String> =
        listOf("content", "file", "data", "marknote-rel")

    private fun mayHaveExif(mimeType: String?): Boolean = when (mimeType?.lowercase()) {
        "image/jpeg", "image/jpg", "image/webp", "image/heif", "image/heic" -> true
        else -> false
    }

    private fun openResolved(uri: Uri): InputStream? = when (uri.scheme) {
        "file" -> uri.path?.let { runCatching { FileInputStream(it) }.getOrNull() }
        else -> runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
    }

    private fun resolveRelativeUri(uri: Uri): Uri = runCatching {
        val segments = uri.pathSegments
        if (segments.isNullOrEmpty()) throw IOException(context.getString(R.string.image_error_generic))
        val treeUri = Uri.parse(segments[0])
        val rel = segments.drop(1)
        if (rel.isEmpty()) throw IOException(context.getString(R.string.image_error_generic))
        val base = uri.getQueryParameter("base").orEmpty()
        resolveUri(treeUri, base, rel)
            ?: (if (base.isNotEmpty()) resolveUri(treeUri, "", rel) else null)
            ?: throw IOException(
                context.getString(
                    R.string.image_not_found,
                    (base.split('/') + rel).filter { it.isNotEmpty() }.joinToString("/"),
                ),
            )
    }.onFailure {
        android.util.Log.w("MarkNote-img", "resolveRelativeUri failed: $uri", it)
    }.getOrThrow()

    private fun resolveUri(treeUri: Uri, base: String, rel: List<String>): Uri? {
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
        return dir.findFile(stack.last())?.takeIf { it.isFile }?.uri
    }

    private fun decodeDataUri(raw: String): ImageItem {
        val comma = raw.indexOf(',')
        if (comma < 0) throw IOException(context.getString(R.string.image_invalid_data))
        val encoded = raw.substring(comma + 1)
        if (encoded.length > MAX_DATA_URI_CHARS) {
            throw IOException(context.getString(R.string.image_decode_data_failed))
        }
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException(context.getString(R.string.image_decode_data_failed))
        }
        val sample = computeSampleSize(bounds.outWidth, bounds.outHeight, maxImageDimension)
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: throw IOException(context.getString(R.string.image_decode_data_failed))
        val orientation = runCatching {
            ByteArrayInputStream(bytes).use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val bitmap = applyExifOrientation(decoded, orientation)
        return ImageItem.withResult(BitmapDrawable(context.resources, bitmap))
    }

    private fun computeSampleSize(width: Int, height: Int, maxDim: Int): Int {
        var sample = 1
        while (width / sample > maxDim || height / sample > maxDim) sample *= 2
        return sample
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        val rotated = runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrNull() ?: return bitmap
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private companion object {
        const val MAX_DATA_URI_CHARS = 12 * 1024 * 1024
        const val MAX_BITMAP_DIM = 2048
    }
}
