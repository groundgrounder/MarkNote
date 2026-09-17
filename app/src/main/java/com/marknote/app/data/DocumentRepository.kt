package com.marknote.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.OpenableColumns
import android.text.format.DateFormat
import com.marknote.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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
 * - 最近打开列表存 SharedPreferences（JSON），重启后仍可访问
 *
 * 关于权限：只有带 FLAG_GRANT_PERSISTABLE_URI_PERMISSION 的授权才能跨进程重启保留。
 * 系统文档选择器（本应用「打开文件/新建」）会给，文件管理器「打开方式」或其他应用
 * 分享过来的 content:// Uri 大多不给（FileProvider、MediaStore 均不支持持久化授权），
 * 这类授权随进程结束一起消失，表现为「退出应用后重新进入提示文件不存在」。
 */
class DocumentRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("recent_docs", Context.MODE_PRIVATE)

    /** 时间格式按界面语言缓存；locale 变化时重建，避免每个列表项都新建 formatter */
    private var timeFormat: SimpleDateFormat? = null
    private var formattedLocale: Locale? = null

    /**
     * 只守 [timeFormat] 的锁。不复用 `@Synchronized`：那会把格式化与 `load()/save()`
     * 串到同一把锁上，列表滚动时会被最近列表的读写堵住。
     */
    private val timeFormatLock = Any()

    // ---------- 读写 ----------

    /**
     * 读取文档全文，并探测出它的编码。读取失败（无权限 / 文件已被移动删除）返回 null，
     * 与「文件存在但内容为空」区分开——否则编辑器会把无权限误显示成空文档。
     *
     * 返回的 [DecodedText] 同时带着写回时要用的编码：markdown 不一定是 UTF-8，把 GBK 文件
     * 按 UTF-8 读进来再按 UTF-8 写回去，会把原文件整体改写成乱码。详见 [TextEncoding]。
     */
    suspend fun readDocument(uri: Uri): DecodedText? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                TextEncoding.decode(input.readBytes())
            }
        }.getOrNull()
    }

    /**
     * 同步读前 limit 字节，用于列表摘要与可访问性探测；失败返回 null。
     * 走 [TextEncoding.decodeTruncated] 而不是直接按 UTF-8 解，否则非 UTF-8 文档的摘要
     * 会和编辑器里显示的内容对不上。
     */
    fun probe(uri: Uri, limit: Int = 512): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(limit)
            val n = input.read(buf)
            if (n > 0) TextEncoding.decodeTruncated(buf.copyOf(n)).text else ""
        }
    }.getOrNull()

    /**
     * 按 [encoding] 写回原文档位置（"wt" 截断模式，部分 provider 不支持时回退 "w"）；
     * 返回是否写成功。编码必须沿用读取时探测出来的那一种，否则会破坏非 UTF-8 的文件。
     */
    suspend fun saveDocument(uri: Uri, content: String, encoding: DocumentEncoding): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = TextEncoding.encode(content, encoding)
                writeTruncating(uri, bytes) || writeAndVerify(uri, bytes)
            }.getOrDefault(false)
        }

    /** 截断写（"wt"）。provider 不支持这个模式时返回 false，交给调用方走退路 */
    private fun writeTruncating(uri: Uri, bytes: ByteArray): Boolean = runCatching {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } != null
    }.getOrDefault(false)

    /**
     * 退路：用 "w" 写，然后核对文件实际长度。
     *
     * "w" 在部分 provider 上**并不截断**，内容变短时旧尾巴会留在文件末尾——文件看着正常，
     * 末尾却多出一段旧文字。核对长度能把这种情况变成一次**可见的失败**（由上层提示用户），
     * 而不是静默留下脏数据。量不到长度时按成功算，保持原有行为，只对「确实短了」下结论。
     */
    private fun writeAndVerify(uri: Uri, bytes: ByteArray): Boolean {
        val written = runCatching {
            context.contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) } != null
        }.getOrDefault(false)
        if (!written) return false
        val size = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        }.getOrNull()
        // ⚠️ getStatSize() 在长度未知时返回 **-1**（不是 null）。早先只判了 null，
        // provider 报 -1 时就被当成「长度不符」→ 明明写成功却给出假的「保存失败」，
        // 用户会以为内容没保住而反复重试。这里把负数一并算作「量不到」，与上面那段注释的口径一致。
        return size == null || size < 0 || size == bytes.size.toLong()
    }

    /**
     * 查询文档显示名。优先问 provider；查不到（已没有权限）时回退到最近列表里
     * 记下的名字，最后才是 Uri 最后一段——避免失效文档标题显示成 "72" 这类 id。
     */
    fun displayName(uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    cursor.getString(idx)?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
        load().firstOrNull { it.uri == uri.toString() }?.name
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return uri.lastPathSegment?.substringAfterLast('/') ?: context.getString(R.string.untitled_md)
    }

    // ---------- 权限持久化 ----------

    /** 是否已持有该 Uri 的持久化读权限（重启后依然有效） */
    fun hasPersistedPermission(uri: Uri): Boolean = context.contentResolver
        .persistedUriPermissions
        .any { it.uri == uri && it.isReadPermission }

    /**
     * 申请持久化权限，返回是否拿到（能跨应用重启保留）。
     *
     * 部分来源的 Uri 系统不允许持久化（回调返回 false），调用方应据此提示用户
     * 重新授权，而不是记进列表后等下次启动才发现打不开。
     */
    fun persistPermission(uri: Uri): Boolean {
        if (hasPersistedPermission(uri)) return true
        val resolver = context.contentResolver
        // 只授了读权限时，带写标志调用会抛 SecurityException，退化后重试
        runCatching {
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure {
            runCatching {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        return hasPersistedPermission(uri)
    }

    /** 当前是否对该文档有写权限（只读打开时保存一定失败，需要提示用户） */
    suspend fun canWrite(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.checkUriPermission(
                uri,
                Process.myPid(),
                Process.myUid(),
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            ) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(true) // 判断不了时按可写处理，避免误报只读
    }

    // ---------- 最近打开列表 ----------

    /** 把 Uri 记入最近列表（置顶），保留既有显示名与图片文件夹授权 */
    @Synchronized
    fun addToRecents(uri: Uri) {
        val key = uri.toString()
        val list = load()
        val old = list.firstOrNull { it.uri == key }
        val entry = Entry(
            uri = key,
            name = old?.name?.ifBlank { null } ?: displayName(uri),
            time = System.currentTimeMillis(),
            tree = old?.tree,
        )
        save(list.filterNot { it.uri == key } + entry)
    }

    /**
     * 重新授权后，用新 Uri 替换旧条目。
     * 同一份文件在不同来源下 Uri 不同（文件管理器分享的 content:// vs 系统选择器的
     * ExternalStorageProvider），替换后最近列表不会多出一条失效记录，位置与图片授权也保留。
     */
    @Synchronized
    fun replaceRecent(oldUriString: String, newUri: Uri) {
        if (oldUriString == newUri.toString()) {
            addToRecents(newUri)
            return
        }
        val list = load()
        val old = list.firstOrNull { it.uri == oldUriString }
        val entry = Entry(
            uri = newUri.toString(),
            name = displayName(newUri).ifBlank { old?.name.orEmpty() },
            time = old?.time ?: System.currentTimeMillis(),
            tree = old?.tree,
        )
        save(list.filterNot { it.uri == oldUriString || it.uri == entry.uri } + entry)
    }

    /** 从最近列表移除（不删除文件本身） */
    @Synchronized
    fun removeFromRecents(uriString: String) {
        save(load().filterNot { it.uri == uriString })
    }

    /**
     * 最近打开列表，按打开时间倒序；附带摘要与可访问性检测。
     *
     * 只探测「读得到读不到」：[DocumentMeta] 不再带可写性，因为列表页不显示它 —— 而
     * 判定可写要走一次跨进程 `checkUriPermission`，每条记录都问一遍纯属白烧。
     * 写权限由编辑器在打开文档时单独查（见 [canWrite]）。
     */
    suspend fun recentDocuments(): List<DocumentMeta> = withContext(Dispatchers.IO) {
        load().sortedByDescending { it.time }.map { entry ->
            val uri = Uri.parse(entry.uri)
            val head = probe(uri)
            DocumentMeta(
                uri = entry.uri,
                name = entry.name.ifBlank { displayName(uri) },
                snippet = head.orEmpty().lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .take(2)
                    .joinToString("  ")
                    .take(80),
                accessible = head != null,
                openedAt = entry.time,
            )
        }
    }

    // ---------- 图片文件夹授权（显示文档相对路径图片用） ----------

    /** 记录「文档 → 图片文件夹（tree Uri）」映射 */
    @Synchronized
    fun setImageTree(docUriString: String, treeUriString: String) {
        save(load().map { if (it.uri == docUriString) it.copy(tree = treeUriString) else it })
    }

    /** 该文档是否已有图片文件夹授权，有则返回 tree Uri 字符串 */
    @Synchronized
    fun imageTreeFor(docUriString: String): String? =
        load().firstOrNull { it.uri == docUriString }?.tree?.ifBlank { null }

    /**
     * 格式化打开时间（列表页展示用）。
     * 日期格式由当前界面语言的 locale 决定（各语言下的字段顺序与分隔符不同），
     * 语言切换后 locale 变化即重建 formatter。
     */
    fun formatTime(epochMillis: Long): String {
        if (epochMillis <= 0) return ""
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        // SimpleDateFormat 不是线程安全的，而这份缓存会被列表里每一项调用；
        // 两个线程同时 format 会把结果算花，所以复制/格式化都得在锁内。
        synchronized(timeFormatLock) {
            if (timeFormat == null || formattedLocale != locale) {
                timeFormat = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, TIME_SKELETON), locale)
                formattedLocale = locale
            }
            return timeFormat!!.format(Date(epochMillis))
        }
    }

    // ---------- 存取 ----------

    private data class Entry(
        val uri: String,
        val name: String,
        val time: Long,
        val tree: String?,
    )

    /**
     * 读取最近列表。返回可变副本，调用方可安全增删。
     * 首次运行新版本时把旧版的三个 StringSet 迁移过来。
     */
    @Synchronized
    private fun load(): List<Entry> {
        val raw = prefs.getString(KEY_DOCS, null) ?: return migrateLegacy()
        parseEntries(raw)?.let { return it }
        // 解析失败不能就这么返回空列表：紧接着的任何一次 save() 都会把空列表写回去，
        // 用户的最近列表就永久没了，而且全程没有任何提示。先把原始串挪到备份键，
        // 留一条能捞回来的路（至少能在 logcat 与 prefs 里找到它）。
        android.util.Log.w(TAG_RECENTS, "最近列表 JSON 解析失败，原文已备份到 $KEY_DOCS_BACKUP")
        runCatching { prefs.edit().putString(KEY_DOCS_BACKUP, raw).apply() }
        return emptyList()
    }

    /** 解析列表 JSON；格式不合法返回 null，由 [load] 决定怎么兜底 */
    private fun parseEntries(raw: String): List<Entry>? = runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val uri = obj.optString(FIELD_URI)
            if (uri.isBlank()) {
                null
            } else {
                Entry(
                    uri = uri,
                    name = obj.optString(FIELD_NAME),
                    time = obj.optLong(FIELD_TIME, 0L),
                    tree = obj.optString(FIELD_TREE).ifBlank { null },
                )
            }
        }
    }.getOrNull()

    /**
     * 写回列表。这里统一按「最近打开」保留最多 [MAX_ENTRIES] 条：列表原本只增不减，
     * 而 [recentDocuments] 对每条都要跨进程 probe 一次正文头，条目越多列表页越慢。
     * 上限放在唯一的写入口，任何增删路径都自动受约束。
     */
    @Synchronized
    private fun save(entries: List<Entry>) {
        val kept = if (entries.size > MAX_ENTRIES) {
            entries.sortedByDescending { it.time }.take(MAX_ENTRIES)
        } else {
            entries
        }
        val arr = JSONArray()
        kept.forEach { e ->
            arr.put(
                JSONObject()
                    .put(FIELD_URI, e.uri)
                    .put(FIELD_NAME, e.name)
                    .put(FIELD_TIME, e.time)
                    .put(FIELD_TREE, e.tree.orEmpty()),
            )
        }
        prefs.edit().putString(KEY_DOCS, arr.toString()).apply()
    }

    /**
     * 旧版（<= 0.8.0）把「Uri<US>名称」「Uri<US>时间戳」分别塞进两个 StringSet，
     * Uri 之间没有任何顺序和归属保证，时间戳也常因解析失败退化成 0。迁移到 JSON 后清除。
     */
    @Synchronized
    private fun migrateLegacy(): List<Entry> {
        val uris = prefs.getStringSet(LEGACY_KEY_URIS, emptySet()).orEmpty()
        if (uris.isEmpty()) return emptyList()
        val names = prefs.getStringSet(LEGACY_KEY_NAMES, emptySet()).orEmpty()
            .associate { it.substringBefore(LEGACY_SEP) to it.substringAfter(LEGACY_SEP, "") }
        val times = prefs.getStringSet(LEGACY_KEY_TIMES, emptySet()).orEmpty()
            .associate {
                it.substringBefore(LEGACY_SEP) to
                    (it.substringAfter(LEGACY_SEP, "0").toLongOrNull() ?: 0L)
            }
        val trees = prefs.getStringSet(LEGACY_KEY_TREES, emptySet()).orEmpty()
            .associate { it.substringBefore(LEGACY_SEP) to it.substringAfter(LEGACY_SEP, "") }
        val entries = uris.map { uri ->
            Entry(
                uri = uri,
                name = names[uri].orEmpty(),
                time = times[uri] ?: 0L,
                tree = trees[uri]?.ifBlank { null },
            )
        }
        save(entries)
        prefs.edit()
            .remove(LEGACY_KEY_URIS)
            .remove(LEGACY_KEY_NAMES)
            .remove(LEGACY_KEY_TIMES)
            .remove(LEGACY_KEY_TREES)
            .apply()
        return entries
    }

    private companion object {
        const val KEY_DOCS = "docs_json"

        /** 列表 JSON 解析失败时，原始串挪到这里，避免被下一次写入静默覆盖掉 */
        const val KEY_DOCS_BACKUP = "docs_json_corrupt_backup"

        const val TAG_RECENTS = "MarkNote-recents"

        /** 最近列表最多保留多少条（超出按打开时间淘汰最旧的） */
        const val MAX_ENTRIES = 100

        const val FIELD_URI = "uri"
        const val FIELD_NAME = "name"
        const val FIELD_TIME = "time"
        const val FIELD_TREE = "tree"

        /** 旧版存储键与分隔符（Unit Separator），仅用于迁移 */
        const val LEGACY_KEY_URIS = "uris"
        const val LEGACY_KEY_NAMES = "names"
        const val LEGACY_KEY_TIMES = "times"
        const val LEGACY_KEY_TREES = "trees"
        const val LEGACY_SEP = "\u0001"

        /** ICU 骨架：年月日时分，具体排列由 locale 决定（如 zh 为 y/M/d HH:mm） */
        const val TIME_SKELETON = "yMdHm"
    }
}
