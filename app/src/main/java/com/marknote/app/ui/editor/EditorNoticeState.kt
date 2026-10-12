package com.marknote.app.ui.editor

enum class EditorNotice {
    SAVE_FAILED,
    READ_ONLY,
    NO_PERSISTED_ACCESS,
}

fun editorNoticeOf(
    saveFailed: Boolean,
    readOnly: Boolean,
    hasPersistedAccess: Boolean,
): EditorNotice? = when {
    saveFailed -> EditorNotice.SAVE_FAILED
    readOnly -> EditorNotice.READ_ONLY
    !hasPersistedAccess -> EditorNotice.NO_PERSISTED_ACCESS
    else -> null
}
