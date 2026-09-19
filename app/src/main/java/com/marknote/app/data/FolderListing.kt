package com.marknote.app.data

/**
 * 文件夹浏览的**纯逻辑**：怎么排序、面包屑怎么拼。
 *
 * 「哪些条目该出现在列表里」在 [isBrowsableEntry]（`TextFileTypes.kt`）—— 那份口径同时被
 * 系统文件选择器用着，放在那边才对得上。
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
