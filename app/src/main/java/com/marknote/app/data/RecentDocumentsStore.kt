package com.marknote.app.data

import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

internal data class RecentEntry(
    val uri: String,
    val name: String,
    val time: Long,
    val tree: String?,
)

internal class RecentDocumentsStore(private val prefs: SharedPreferences) {

    @Synchronized
    fun entries(): List<RecentEntry> = load()

    @Synchronized
    fun nameFor(uri: String): String? = load().firstOrNull { it.uri == uri }?.name

    @Synchronized
    fun treeFor(uri: String): String? = load().firstOrNull { it.uri == uri }?.tree?.ifBlank { null }

    @Synchronized
    fun add(uri: String, name: String, tree: String?, time: Long) {
        val current = load()
        val old = current.firstOrNull { it.uri == uri }
        val entry = RecentEntry(
            uri = uri,
            name = name,
            time = time,
            tree = tree ?: old?.tree,
        )
        save(current.filterNot { it.uri == uri } + entry)
    }

    @Synchronized
    fun replace(oldUri: String, entry: RecentEntry) {
        save(load().filterNot { it.uri == oldUri || it.uri == entry.uri } + entry)
    }

    @Synchronized
    fun remove(uri: String) {
        save(load().filterNot { it.uri == uri })
    }

    @Synchronized
    fun setTree(uri: String, tree: String) {
        save(load().map { if (it.uri == uri) it.copy(tree = tree) else it })
    }

    @Synchronized
    private fun load(): List<RecentEntry> {
        val raw = prefs.getString(KEY_DOCS, null) ?: return migrateLegacy()
        parseEntries(raw)?.let { return it }

        val salvaged = salvageEntries(raw)
        Log.w(TAG, "最近列表损坏，已恢复 ${salvaged.size} 条记录")
        runCatching {
            prefs.edit()
                .putString(KEY_DOCS_BACKUP, raw)
                .putString(KEY_DOCS, encode(salvaged))
                .apply()
        }
        return salvaged
    }

    private fun parseEntries(raw: String): List<RecentEntry>? = runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { entryOf(array.optJSONObject(it)) }
    }.getOrNull()

    private fun salvageEntries(raw: String): List<RecentEntry> =
        salvageEntryJson(raw).mapNotNull { json ->
            runCatching { entryOf(JSONObject(json)) }.getOrNull()
        }

    private fun entryOf(obj: JSONObject?): RecentEntry? {
        val value = obj ?: return null
        val uri = value.optString(FIELD_URI)
        if (uri.isBlank()) return null
        return RecentEntry(
            uri = uri,
            name = value.optString(FIELD_NAME),
            time = value.optLong(FIELD_TIME, 0L),
            tree = value.optString(FIELD_TREE).ifBlank { null },
        )
    }

    private fun save(entries: List<RecentEntry>) {
        val kept = entries
            .sortedByDescending { it.time }
            .take(MAX_ENTRIES)
        prefs.edit().putString(KEY_DOCS, encode(kept)).apply()
    }

    private fun encode(entries: List<RecentEntry>): String {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put(FIELD_URI, entry.uri)
                    .put(FIELD_NAME, entry.name)
                    .put(FIELD_TIME, entry.time)
                    .put(FIELD_TREE, entry.tree.orEmpty()),
            )
        }
        return array.toString()
    }

    private fun migrateLegacy(): List<RecentEntry> {
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
            RecentEntry(uri, names[uri].orEmpty(), times[uri] ?: 0L, trees[uri]?.ifBlank { null })
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
        const val KEY_DOCS_BACKUP = "docs_json_corrupt_backup"
        const val TAG = "MarkNote-recents"
        const val MAX_ENTRIES = 100
        const val FIELD_URI = "uri"
        const val FIELD_NAME = "name"
        const val FIELD_TIME = "time"
        const val FIELD_TREE = "tree"
        const val LEGACY_KEY_URIS = "uris"
        const val LEGACY_KEY_NAMES = "names"
        const val LEGACY_KEY_TIMES = "times"
        const val LEGACY_KEY_TREES = "trees"
        const val LEGACY_SEP = "\u0001"
    }
}

