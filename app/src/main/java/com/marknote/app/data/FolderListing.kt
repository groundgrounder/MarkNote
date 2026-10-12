package com.marknote.app.data

data class FolderEntry(
    val name: String,
    val uri: String,
    val isDirectory: Boolean,
)

fun sortFolderEntries(entries: List<FolderEntry>): List<FolderEntry> =
    entries.sortedWith(
        compareByDescending<FolderEntry> { it.isDirectory }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            .thenBy { it.uri },
    )

fun folderDisplayName(documentId: String): String? =
    documentId.substringAfterLast('/').substringAfterLast(':').ifBlank { null }
