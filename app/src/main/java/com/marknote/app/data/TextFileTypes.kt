package com.marknote.app.data

/**
 * 「哪些文件算文本文件」—— 判定口径的唯一来源：[isBrowsableEntry] 决定哪些条目进文件夹浏览的
 * 列表，那份列表同时也是「打开文件」的去处。
 *
 * **为什么这件事不能交给系统文件选择器**（2026-09-19 在 API 36 上实测，用 adb 直接发意图做对照）：
 * `ACTION_OPEN_DOCUMENT` 的类型过滤（`EXTRA_MIME_TYPES` 或 `setType`）**只对「最近」根视图生效**。
 * 用户一旦从「内部存储」翻进具体目录，过滤就被完全忽略 —— APK、zip、png、PDF 照原样列出来；
 * 换成 `text/` 通配、换成精确 MIME、换成 `application/vnd.android.package-archive` 都一样，
 * 只有 `image/`、`audio/`、`video/` 三类在目录内会被过滤。平台把非媒体类型的过滤下推给 provider
 * （`DocumentsContract.QUERY_ARG_MIME_TYPES`），而 ExternalStorageProvider 不认这个查询参数。
 *
 * 所以「看不到不能编辑的文件」只能由应用内这一层保证（`FileListScreen` 的「打开文件」）。
 * 另有两条容易造成误判的坑：`EXTRA_MIME_TYPES` 里混进全通配项（星号加斜杠加星号）会让过滤
 * 彻底失效（规则是「任意一项匹配即显示」）；而「最近」恰好是默认落点，只看打开那一刻会以为
 * 过滤全都生效了。两条都得单独验。
 *
 * 与 [TextEncoding]、[FolderListing] 同一条约定：这个文件**不 import 任何 Android 类型**，
 * 上面的纯逻辑可以直接跑 tools/checks 的 JVM 断言。
 */

/** 目录的 MIME 类型（`DocumentsContract.Document.MIME_TYPE_DIR` 的值，写死在这里以免引入 Android 依赖） */
private const val MIME_DIR = "vnd.android.document/directory"

/**
 * provider 报的这个 MIME 算不算文本。
 *
 * 认 `text/` 整族。下面这些取值都是 2026-09-19 在 API 36 上查各文件的 `COLUMN_MIME_TYPE`
 * 实测到的：`.md`→`text/markdown`、`.txt`→`text/plain`、`.py`→`text/x-python`、
 * `.html`→`text/html`，而 `.png`→`image/png`、`.zip`→`application/zip`、
 * 无扩展名→`application/octet-stream`。
 */
fun isTextMime(mimeType: String): Boolean = mimeType.startsWith("text/", ignoreCase = true)

/**
 * 值得列进文件夹的文本扩展名。
 *
 * 与「打开方式」（AndroidManifest 里的 intent-filter）只放 .md / .markdown / .txt 保持一致。
 */
private val TEXT_EXTENSIONS = setOf("md", "markdown", "txt")

/**
 * 一定不是文本的扩展名 —— **一票否决，优先于 MIME**。
 *
 * 为什么需要它：部分 provider（第三方网盘、云盘客户端）不管什么文件一律报 `text/plain`，
 * 只按 MIME 判就会让图片、压缩包混进列表，点开是一屏乱码。扩展名是文件自己带的，
 * 比 provider 给的 MIME 可信。
 */
private val BINARY_EXTENSIONS = setOf(
    // 图片
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "heic", "heif", "tif", "tiff", "avif",
    // 文档
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "epub", "mobi",
    // 压缩包
    "zip", "rar", "7z", "gz", "tgz", "bz2", "xz", "zst", "lz4", "tar",
    // 安装包与二进制
    "apk", "aab", "jar", "dex", "class", "so", "dylib", "dll", "exe", "msi", "deb", "rpm", "dmg",
    "bin", "dat", "db", "sqlite", "sqlite3",
    // 音视频
    "mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "amr",
    "mp4", "m4v", "mkv", "mov", "avi", "webm", "wmv", "flv", "3gp",
    // 字体
    "ttf", "otf", "woff", "woff2", "eot",
)

/** 名字以 `.` 开头就是隐藏文件 —— `.git`、`.obsidian`、`.DS_Store`，笔记目录里最常见的噪声 */
fun isHiddenName(name: String): Boolean = name.startsWith(".")

/**
 * 这个条目值不值得出现在文件夹列表里。
 *
 * [showHiddenFiles] 为 false 时把隐藏的目录与文件一起滤掉 —— 目录也是条目，`.git` 点进去
 * 没有任何意义，只是多一层要退出来的东西。
 *
 * MIME 与扩展名两条路都要走：
 * 1. **扩展名黑名单一票否决**（见 [BINARY_EXTENSIONS]）；
 * 2. **扩展名白名单**（.md / .markdown / .txt）——
 *    本应用本来就能编辑纯文本，而文件夹里往往混着 README、笔记草稿这类 .txt；
 * 3. 都不命中时看 MIME，是 `text/` 一族就收（覆盖 `.py`、`.html` 这些源码与网页）。
 *
 * 不看大小写：`README.MD` 在手机上下载下来很常见。
 *
 * ⚠️ 注释里别出现「斜杠紧跟星号」的字面量（比如 MIME 的通配写法）：Kotlin 的块注释
 * **可以嵌套**，那两个字符合起来会再开一层注释，整个文件报「Unclosed comment」——
 * 本文件的前身就是这么挂过一次，连「提醒别这么写」的那句话本身也算一次。
 */
fun isBrowsableEntry(name: String, mimeType: String, showHiddenFiles: Boolean): Boolean {
    if (!showHiddenFiles && isHiddenName(name)) return false
    if (mimeType == MIME_DIR) return true
    val extension = extensionOf(name)
    if (extension != null && extension in BINARY_EXTENSIONS) return false
    if (extension != null && extension in TEXT_EXTENSIONS) return true
    return isTextMime(mimeType)
}

/** 小写扩展名（不含点）；没有扩展名、或名字以点结尾时返回 null */
private fun extensionOf(name: String): String? {
    val dot = name.lastIndexOf('.')
    if (dot < 0 || dot == name.length - 1) return null
    return name.substring(dot + 1).lowercase()
}
