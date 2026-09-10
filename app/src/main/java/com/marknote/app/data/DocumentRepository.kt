package com.marknote.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 最近打开列表中的一项 */
data class DocumentMeta(
    val uri: String,
    val name: String,          // 显示文件名（含后缀）
    val snippet: String,       // 正文摘要，读取失败时为空
    val accessible: Boolean,   // 当前是否还能读到该文档
    val openedAt: Long,
)

/**
 * 文档仓库：基于 SAF（Storage Access Framework）读写任意位置的 .md 文件。
 * - 打开/新建通过系统文档选择器拿到 Uri，并申请持久化读权限
 * - 最近打开列表存 SharedPreferences，重启后仍可访问
 */
class DocumentRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("recent_docs", Context.MODE_PRIVATE)
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    // ---------- 读写 ----------

    /** 读取文档全文，失败返回空串（不崩溃） */
    suspend fun read(uri: Uri): String = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }
        }.getOrNull().orEmpty()
    }

    /** 写回原文档位置（"wt" 截断模式，部分 provider 不支持时回退 "w"） */
    suspend fun save(uri: Uri, content: String) = withContext(Dispatchers.IO) {
        runCatching {
            val written = runCatching {
                context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                } != null
            }.getOrDefault(false)
            if (!written) {
                context.contentResolver.openOutputStream(uri, "w")?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    /** 查询文档显示名，查不到时回退到 Uri 最后一段 */
    fun displayName(uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    cursor.getString(idx)?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "未命名.md"
    }

    // ---------- 权限持久化 ----------

    /** 对系统选择器 / 外部 intent 返回的 Uri 申请长期读权限（尽力而为） */
    fun persistPermission(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    // ---------- 最近打开列表 ----------

    /** 把 Uri 记入最近列表（置顶），同时记录显示名与打开时间 */
    fun addToRecents(uri: Uri) {
        val key = uri.toString()
        val uris = (prefs.getStringSet(KEY_URIS, emptySet()).orEmpty() - key) + key
        val names = prefs.getStringSet(KEY_NAMES, emptySet()).orEmpty()
            .filterNot { it.substringBefore(SEP) == key }.toSet() + "$key$SEP${displayName(uri)}"
        val times = prefs.getStringSet(KEY_TIMES, emptySet()).orEmpty()
            .filterNot { it.substringBefore(SEP) == key }.toSet() + "$key$SEP${System.currentTimeMillis()}"
        prefs.edit()
            .putStringSet(KEY_URIS, uris)
            .putStringSet(KEY_NAMES, names)
            .putStringSet(KEY_TIMES, times)
            .apply()
    }

    /** 从最近列表移除（不删除文件本身） */
    fun removeFromRecents(uriString: String) {
        prefs.edit()
            .putStringSet(KEY_URIS, prefs.getStringSet(KEY_URIS, emptySet()).orEmpty() - uriString)
            .putStringSet(KEY_NAMES, prefs.getStringSet(KEY_NAMES, emptySet()).orEmpty()
                .filterNot { it.substringBefore(SEP) == uriString }.toSet())
            .putStringSet(KEY_TIMES, prefs.getStringSet(KEY_TIMES, emptySet()).orEmpty()
                .filterNot { it.substringBefore(SEP) == uriString }.toSet())
            .apply()
    }

    /** 最近打开列表，按打开时间倒序；附带摘要与可访问性检测 */
    suspend fun recentDocuments(): List<DocumentMeta> = withContext(Dispatchers.IO) {
        val uris = prefs.getStringSet(KEY_URIS, emptySet()).orEmpty()
        val names = prefs.getStringSet(KEY_NAMES, emptySet()).orEmpty()
            .associate { it.substringBefore(SEP) to it.substringAfter(SEP, "") }
        val times = prefs.getStringSet(KEY_TIMES, emptySet()).orEmpty()
            .associate {
                it.substringBefore(SEP) to (it.substringAfter(SEP, "0").toLongOrNull() ?: 0L)
            }
        uris.map { key ->
            val uri = Uri.parse(key)
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val buf = ByteArray(512)
                    val n = input.read(buf)
                    if (n > 0) String(buf, 0, n, Charsets.UTF_8) else ""
                }
            }.getOrNull()
            DocumentMeta(
                uri = key,
                name = names[key]?.ifBlank { null } ?: displayName(uri),
                snippet = text.orEmpty().lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .take(2)
                    .joinToString("  ")
                    .take(80),
                accessible = text != null,
                openedAt = times[key] ?: 0L,
            )
        }.sortedByDescending { it.openedAt }
    }

    // ---------- 图片文件夹授权（显示文档相对路径图片用） ----------

    /** 记录"文档 → 图片文件夹（tree Uri）"映射 */
    fun setImageTree(docUriString: String, treeUriString: String) {
        val trees = prefs.getStringSet(KEY_TREES, emptySet()).orEmpty()
            .filterNot { it.substringBefore(SEP) == docUriString }.toSet() +
            "$docUriString$SEP$treeUriString"
        prefs.edit().putStringSet(KEY_TREES, trees).apply()
    }

    /** 该文档是否已有图片文件夹授权，有则返回 tree Uri 字符串 */
    fun imageTreeFor(docUriString: String): String? =
        prefs.getStringSet(KEY_TREES, emptySet()).orEmpty()
            .firstOrNull { it.substringBefore(SEP) == docUriString }
            ?.substringAfter(SEP, "")?.ifBlank { null }

    /** 格式化打开时间（列表页展示用） */
    fun formatTime(epochMillis: Long): String =
        if (epochMillis > 0) timeFormat.format(Date(epochMillis)) else ""

    private companion object {
        const val KEY_URIS = "uris"
        const val KEY_NAMES = "names"
        const val KEY_TIMES = "times"
        const val KEY_TREES = "trees"
        const val SEP = "" // Uri 与名称/时间戳的分隔符（Unit Separator，不可能出现在 Uri 中）
    }
}
