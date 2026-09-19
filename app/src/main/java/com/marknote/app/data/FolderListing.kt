package com.marknote.app.data

/**
 * 文件夹浏览的**纯逻辑**：哪些条目该出现在列表里、怎么排序、面包屑怎么拼。
 *
 * 与 [TextEncoding] 同一条约定：这个文件**不 import 任何 Android 类型** ——
 * SAF 那边（DocumentsContract / ContentResolver）在 DocumentRepository 里，
 * 这里只留「拿到一列名字和 MIME 之后怎么处理」，好让 tools/checks 的 JVM 断言直接跑。
 */

/** 文件夹里的一个条目：目录或文件。uri 是树授权下的文档 Uri（可直接交给编辑器打开） */
data class FolderEntry(
    val name: String,
    val uri: String,
    val isDirectory: Boolean,
)

/** 目录的 MIME 类型（DocumentsContract.Document.MIME_TYPE_DIR 的值，写死在这里以免引入 Android 依赖） */
private const val MIME_DIR = "vnd.android.document/directory"

/**
 * 这个文件值不值得出现在文件夹列表里。
 *
 * 口径与「打开方式」一致：`.md` / `.markdown` / `.txt` 与 `text/` 开头的 MIME 都收 ——
 * 本应用本来就能编辑纯文本（README 里也这么写），而文件夹里往往混着 README、笔记草稿这类 .txt。
 * 目录一律收（那是导航用的）。
 *
 * ⚠️ 注释里别出现「斜杠紧跟星号」的字面量（比如 MIME 的通配写法）：Kotlin 的块注释
 * **可以嵌套**，那两个字符合起来会再开一层注释，整个文件报「Unclosed comment」——
 * 本文件第一版就是这么挂的，连「提醒别这么写」的那句话本身也算一次。
 *
 * 不看大小写：`README.MD` 在手机上下载下来很常见。
 */
fun isBrowsableEntry(name: String, mimeType: String): Boolean {
    if (mimeType == MIME_DIR) return true
    val lower = name.lowercase()
    if (lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".txt")) return true
    return mimeType.startsWith("text/")
}

/**
 * 列表排序：**目录在前**，同类按名字（大小写不敏感）。
 *
 * 大小写不敏感是必须的：`Zebra.md` 排在 `apple.md` 前面会让人以为列表坏了。
 * 名字相同时按 uri 兜底，保证顺序稳定（不然每次刷新顺序都变，看着像在闪）。
 */
fun sortFolderEntries(entries: List<FolderEntry>): List<FolderEntry> =
    entries.sortedWith(
        compareByDescending<FolderEntry> { it.isDirectory }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            .thenBy { it.uri },
    )

/**
 * 面包屑要显示的文件夹名：从 SAF 的文档 id 里取。
 *
 * 文档 id 长这样：`primary:Documents/notes`（外部存储）、`msf:1000000023`（媒体库）——
 * 取最后一个分段即可；取不出名字时返回 null，调用方退回不显示这一段。
 */
fun folderDisplayName(documentId: String): String? =
    documentId.substringAfterLast('/').substringAfterLast(':').ifBlank { null }
