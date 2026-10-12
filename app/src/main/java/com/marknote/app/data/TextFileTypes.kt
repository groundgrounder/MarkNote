package com.marknote.app.data

private const val MIME_DIR = "vnd.android.document/directory"

fun isTextMime(mimeType: String): Boolean = mimeType.startsWith("text/", ignoreCase = true)

private val TEXT_EXTENSIONS = setOf("md", "markdown", "txt")

private val BINARY_EXTENSIONS = setOf(

    "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "heic", "heif", "tif", "tiff", "avif",

    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "epub", "mobi",

    "zip", "rar", "7z", "gz", "tgz", "bz2", "xz", "zst", "lz4", "tar",

    "apk", "aab", "jar", "dex", "class", "so", "dylib", "dll", "exe", "msi", "deb", "rpm", "dmg",
    "bin", "dat", "db", "sqlite", "sqlite3",

    "mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "amr",
    "mp4", "m4v", "mkv", "mov", "avi", "webm", "wmv", "flv", "3gp",

    "ttf", "otf", "woff", "woff2", "eot",
)

fun isHiddenName(name: String): Boolean = name.startsWith(".")

fun isBrowsableEntry(name: String, mimeType: String, showHiddenFiles: Boolean): Boolean {
    if (!showHiddenFiles && isHiddenName(name)) return false
    if (mimeType == MIME_DIR) return true
    val extension = extensionOf(name)
    if (extension != null && extension in BINARY_EXTENSIONS) return false
    if (extension != null && extension in TEXT_EXTENSIONS) return true
    return isTextMime(mimeType)
}

private fun extensionOf(name: String): String? {
    val dot = name.lastIndexOf('.')
    if (dot < 0 || dot == name.length - 1) return null
    return name.substring(dot + 1).lowercase()
}
