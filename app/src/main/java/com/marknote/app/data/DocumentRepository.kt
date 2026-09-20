package com.marknote.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.system.Os
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
                if (!writeOnce(uri, "wt", bytes) && !writeOnce(uri, "w", bytes)) {
                    return@runCatching false
                }
                // 两条路都要核长度 —— 理由见 [lengthMatches]，别只核退路那条
                lengthMatches(uri, bytes)
            }.getOrDefault(false)
        }

    /** 按指定模式写一整份字节。打不开（provider 不支持这个模式）或写失败都返回 false */
    private fun writeOnce(uri: Uri, mode: String, bytes: ByteArray): Boolean = runCatching {
        context.contentResolver.openOutputStream(uri, mode)?.use { it.write(bytes) } != null
    }.getOrDefault(false)

    /**
     * 核对文件实际长度是不是刚写进去的那份。
     *
     * **不只是退路要核**：`"wt"` 这名字里虽然有 truncate，但截断是 provider 自己该做的事，
     * SAF 层面并不强制——provider 完全可以收下这个模式、却按普通 "w" 处理。这样一来
     * 内容变短时旧尾巴会留在文件末尾：文件看着正常，末尾却多出一段旧文字。
     * 核长度能把这种情况变成一次**可见的失败**（由上层提示用户，见 EditorViewModel.saveFailed），
     * 而不是静默留下脏数据。早先只有退路的 "w" 那条路核，"wt" 返回 true 就直接算成功了，
     * 盲区正好落在最常用的那条路径上（2026-09-20）。
     *
     * 量不到长度时按成功算：那种情况下没有任何证据说它写坏了，报失败只会制造假的「保存失败」。
     */
    private fun lengthMatches(uri: Uri, bytes: ByteArray): Boolean {
        val size = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        }.getOrNull()
        // ⚠️ getStatSize() 在长度未知时返回 **-1**（不是 null）。早先只判了 null，
        // provider 报 -1 时就被当成「长度不符」→ 明明写成功却给出假的「保存失败」，
        // 用户会以为内容没保住而反复重试。这里把负数一并算作「量不到」，与上面那段口径一致。
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

    /**
     * 文件的**身份**：本地文件的 `(st_dev, st_ino)`。
     *
     * 同一份文件从不同来源拿到的 Uri 是不一样的（文件管理器给 `content://media/external/file/<id>`，
     * 系统选择器给 `content://com.android.externalstorage.documents/document/primary%3A...`），
     * 但两个 Uri 打开同一个文件时算出的身份相同 —— 靠它判断「两块窗口是不是在编辑同一个文件」。
     *
     * 拿不到文件描述符时（远端 provider 把文件当流给）返回 null，调用方退回按 Uri 判断：
     * **宁可多开一个窗口，也不要误挡**。
     */
    suspend fun fileIdentity(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val stat = Os.fstat(pfd.fileDescriptor)
                "${stat.st_dev}:${stat.st_ino}"
            }
        }.getOrNull()
    }

    // ---------- 权限持久化 ----------

    /**
     * 是否已持有该 Uri 的持久化读权限（重启后依然有效）。
     *
     * 除了「正好就是这个 Uri」，还认**落在某个已持久化的树授权里**的子文档 ——
     * 文件夹浏览打开的就是这种 Uri。SAF **不允许**给子文档单独持久化（只有树 Uri 能），
     * 所以只比 `it.uri == uri` 会把它们判成「没有长期授权」：每次从最近列表打开都会弹
     * 「这个文件无法长期访问」并让用户重选一次，而文件其实一直打得开（2026-09-19 实测到）。
     */
    fun hasPersistedPermission(uri: Uri): Boolean = context.contentResolver
        .persistedUriPermissions
        .any { permission ->
            permission.isReadPermission &&
                (permission.uri == uri || uri.toString().startsWith(permission.uri.toString() + "/"))
        }

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
    fun addToRecents(uri: Uri, treeUriString: String? = null) {
        val key = uri.toString()
        val list = load()
        val old = list.firstOrNull { it.uri == key }
        val entry = Entry(
            uri = key,
            name = old?.name?.ifBlank { null } ?: displayName(uri),
            time = System.currentTimeMillis(),
            // 从文件夹浏览里打开的文档直接沿用该文件夹当图片根：相对路径图片最常见的形态
            // 就是「图和文档在同一个文件夹」，这样用户不必再单独授权一次
            tree = treeUriString ?: old?.tree,
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

    // ---------- 文件夹浏览（SAF 树授权） ----------

    /**
     * 当前选中的文件夹（树 Uri 字符串）；没选过、或授权已经失效时返回 null。
     *
     * 记在 prefs 里**不代表授权还在**（重装、用户撤销、系统回收都会让它失效）—— 与图片文件夹
     * 那条同一个道理：失效时按「没选过」处理，界面会重新显示「选择文件夹」，
     * 而不是列出一片打不开的条目让人以为文件坏了。
     */
    fun folderTree(): String? {
        val stored = prefs.getString(KEY_FOLDER_TREE, null)?.ifBlank { null } ?: return null
        return if (hasPersistedPermission(Uri.parse(stored))) stored else null
    }

    /** 记下选中的文件夹（传 null 表示取消选择） */
    fun setFolderTree(treeUriString: String?) {
        prefs.edit().putString(KEY_FOLDER_TREE, treeUriString).apply()
    }

    /**
     * 记在 prefs 里的那个文件夹（**不检查授权**）。
     * 界面用它区分「从没选过」与「选过但授权失效了」—— 两种情况该说的话不一样。
     */
    fun folderTreeStored(): String? = prefs.getString(KEY_FOLDER_TREE, null)?.ifBlank { null }

    /**
     * 申请文件夹（树）的持久化权限 —— 在 `ACTION_OPEN_DOCUMENT_TREE` 的回调里调。
     * 走 [persistPermission] 同一条路（带写标志失败时退化为只读重试）。
     */
    fun persistFolderPermission(treeUri: Uri): Boolean = persistPermission(treeUri)

    /** 面包屑里根文件夹的显示名（拿不到返回 null，调用方只显示子目录） */
    fun folderRootName(treeUri: Uri): String? =
        runCatching { folderDisplayName(DocumentsContract.getTreeDocumentId(treeUri)) }.getOrNull()

    /**
     * 列出一个文件夹的直接子项（目录在前，只保留可浏览的文本文件）。
     *
     * [folderUri] 是树授权下的文档 Uri：**根文件夹直接传树 Uri**，子目录传列表里给出的那个 Uri。
     * 两者的文档 id 取法不同（树 Uri 要用 `getTreeDocumentId`），所以这里两种都兜住。
     *
     * 子项 Uri 一律用 [DocumentsContract.buildDocumentUriUsingTree] 重建 —— 只有带 tree 段的
     * Uri 才能被编辑器继续读写（去掉 tree 段就是一个没有授权的裸文档 Uri）。
     *
     * [showHiddenFiles] 为 false 时滤掉名字以 `.` 开头的条目。**在这里滤而不是在界面里滤**：
     * 界面拿到的就是最终列表，「空文件夹」那一句提示才对得上（否则会出现「列表是空的，
     * 却说这个文件夹里有东西」）。
     *
     * 失败（授权没了、provider 抽风）返回空列表：界面会显示「这个文件夹是空的」，
     * 比抛出去崩掉更接近用户能理解的状态；真要排查有 logcat。
     */
    suspend fun listFolder(
        treeUri: Uri,
        folderUri: Uri,
        showHiddenFiles: Boolean,
    ): List<FolderEntry> = withContext(Dispatchers.IO) {
        val documentId = runCatching { DocumentsContract.getDocumentId(folderUri) }
            .getOrElse { DocumentsContract.getTreeDocumentId(folderUri) }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val entries = mutableListOf<FolderEntry>()
        runCatching {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2).orEmpty()
                    if (!isBrowsableEntry(name, mime, showHiddenFiles)) continue
                    entries += FolderEntry(
                        name = name,
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString(),
                        isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    )
                }
            }
        }.onFailure {
            android.util.Log.w(TAG_RECENTS, "列文件夹失败：$folderUri", it)
        }
        sortFolderEntries(entries)
    }

    // ---------- 文件夹里的写操作（新建 / 重命名 / 删除） ----------

    /**
     * 在 [parentUri] 里新建一个文档，返回它的 Uri。
     *
     * [mimeType] 传目录的 MIME 就是**新建文件夹** —— SAF 里两者是同一个调用，只有类型不同。
     *
     * ⚠️ 两个必须兜住的地方（都是实测踩到的）：
     * 1. [parentUri] 可能是**树 Uri 本身** —— 在授权文件夹的根目录下新建时就是它。而
     *    `createDocument` 要的是**文档** Uri，直接传树 Uri 会失败（返回 null，界面上只是
     *    「新建失败」，看不出原因）。按 [listFolder] 那套同样取一次 documentId 再重建；
     * 2. `createDocument` 返回的 Uri **不带 tree 段**，编辑器拿它读写打不开，得用 tree +
     *    新的 document id 重建一遍。
     *
     * 失败返回 null：只读授权、名字里有 `/` 之类非法字符、provider 不支持写，都会走到这里。
     * 交给界面提示，不往外抛。
     */
    suspend fun createDocument(
        treeUri: Uri,
        parentUri: Uri,
        displayName: String,
        mimeType: String,
    ): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val parentId = runCatching { DocumentsContract.getDocumentId(parentUri) }
                .getOrElse { DocumentsContract.getTreeDocumentId(parentUri) }
            val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)

            val created = DocumentsContract.createDocument(
                context.contentResolver,
                parentDocUri,
                mimeType,
                displayName,
            ) ?: return@runCatching null
            DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getDocumentId(created),
            )
        }.getOrNull()
    }

    /**
     * 重命名。返回是否成功。
     *
     * 成功后 document id 可能变（改名等于换文档），所以调用方重列一次列表就行，
     * 不要试图继续用旧 Uri。
     */
    suspend fun renameDocument(uri: Uri, newName: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            DocumentsContract.renameDocument(context.contentResolver, uri, newName) != null
        }.getOrDefault(false)
    }

    /**
     * 删除。**不可撤销** —— 本机的 ExternalStorageProvider 是直接删掉，不进回收站
     * （云盘 provider 可能进它自己的回收站，但不能依赖）。调用方**必须先向用户确认**。
     */
    suspend fun deleteDocument(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        }.getOrDefault(false)
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
     * 读取最近列表。返回可安全增删的副本。
     * 首次运行新版本时把旧版的三个 StringSet 迁移过来。
     */
    @Synchronized
    private fun load(): List<Entry> {
        val raw = prefs.getString(KEY_DOCS, null) ?: return migrateLegacy()
        parseEntries(raw)?.let { return it }
        // 走到这里说明整份 JSON 都解析不了。先尽力抢救一遍：实际会遇到的坏法几乎都是
        // 尾部截断（写入被中断），前面那些条目其实是好的，按「全坏」处理会把整份列表
        // 清空，而它们本该能救回来。
        val salvaged = salvageEntries(raw)
        // 原文一律留一份备份：抢救结果可能不完整（文件名带花括号的那几条会漏掉），
        // 也可能一条都没救回来 —— 备份是事后唯一能捞的东西。
        android.util.Log.w(
            TAG_RECENTS,
            "最近列表 JSON 解析失败：从 $KEY_DOCS 抢救出 ${salvaged.size} 条，原文备份到 $KEY_DOCS_BACKUP",
        )
        // 主键改写成抢救结果（哪怕是空）：坏数据留在主键里，只会让之后每次读取都重新
        // 告警一遍，而它已经没用了。处理完两个键各自自洽 —— 一个能读，一个留着原文。
        runCatching {
            prefs.edit()
                .putString(KEY_DOCS_BACKUP, raw)
                .putString(KEY_DOCS, encode(salvaged))
                .apply()
        }
        return salvaged
    }

    /** 解析列表 JSON；格式不合法返回 null，由 [load] 决定怎么兜底 */
    private fun parseEntries(raw: String): List<Entry>? = runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { entryOf(arr.optJSONObject(it)) }
    }.getOrNull()

    /**
     * 尽力抢救：整份 JSON 解析不了时，把里面还完整的条目抠出来。
     *
     * 实际会遇到的坏法几乎都是写入中断造成的**尾部截断**——前面那些条目其实是好的。
     * 直接按「全坏」处理会把整份最近列表清空，而它们本该能救回来。
     * 抠取的口径与边界见 [salvageEntryJson]。
     */
    private fun salvageEntries(raw: String): List<Entry> =
        salvageEntryJson(raw).mapNotNull { json ->
            runCatching { entryOf(JSONObject(json)) }.getOrNull()
        }

    /** 一个 JSON 对象 → 一条记录；缺 uri 的条目作废（返回 null） */
    private fun entryOf(obj: JSONObject?): Entry? {
        val o = obj ?: return null
        val uri = o.optString(FIELD_URI)
        if (uri.isBlank()) return null
        return Entry(
            uri = uri,
            name = o.optString(FIELD_NAME),
            time = o.optLong(FIELD_TIME, 0L),
            tree = o.optString(FIELD_TREE).ifBlank { null },
        )
    }

    @Synchronized
    private fun save(entries: List<Entry>) {
        val kept = if (entries.size > MAX_ENTRIES) {
            entries.sortedByDescending { it.time }.take(MAX_ENTRIES)
        } else {
            entries
        }
        prefs.edit().putString(KEY_DOCS, encode(kept)).apply()
    }

    /**
     * 序列化成 JSON 数组串。抢救路径（[load]）与正常写回（[save]）共用同一份写法 ——
     * 两处各写一遍，迟早会出现「写得进去却读不出来」的错配。
     */
    private fun encode(entries: List<Entry>): String {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject()
                    .put(FIELD_URI, e.uri)
                    .put(FIELD_NAME, e.name)
                    .put(FIELD_TIME, e.time)
                    .put(FIELD_TREE, e.tree.orEmpty()),
            )
        }
        return arr.toString()
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

        /** 文件夹浏览里选中的那个文件夹（SAF 树 Uri） */
        const val KEY_FOLDER_TREE = "folder_tree"

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

/**
 * 从坏掉的最近列表 JSON 里抠出**还完整的条目子串**（不做 JSON 解析，那一步留给调用方）。
 *
 * 实际会遇到的坏法几乎都是写入中断造成的**尾部截断**，前面那些条目其实是好的，
 * 直接按「全坏」处理会把整份列表清空，而它们本该能救回来。
 *
 * 之所以能这么扫：条目是**扁平**的（uri / name / time / tree 都是标量，见 Entry），
 * 一对花括号就是一个条目。文件名里若带花括号会让某一条抠得不完整（子串解析会失败、
 * 那条被跳过）—— 抢救的底线是「宁可少救一条，也不能救出错的」。
 *
 * 单独抽成顶层函数是为了能进断言：org.json 只在 Android 里有，JVM 断言走不到
 * [DocumentRepository.salvageEntries]，但这一层纯字符串扫描可以（见 CheckRecentsSalvage）。
 */
internal fun salvageEntryJson(raw: String): List<String> =
    Regex("\\{[^{}]*\\}").findAll(raw).map { it.value }.toList()
