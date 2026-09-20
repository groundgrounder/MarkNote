package com.marknote.app.ui.editor

/**
 * 编辑器顶部该显示哪一条提示。
 *
 * **一次只出一条**：这几种情况会同时成立 —— MediaStore 来源的文件既只读、又拿不到持久授权，
 * 保存失败之后更是三条全中。所以必须定优先级，而且**顺序不能弄反**：反了会让一个只读文件
 * 显示「退出应用后打不开」这种和它无关的说明，用户照做去重新授权，真正的问题却不在那儿。
 *
 * 顺序按「后果从重到轻」：
 * 1. [SAVE_FAILED] —— 已经丢过一次改动了；
 * 2. [READ_ONLY] —— 改动根本不会落盘；
 * 3. [NO_PERSISTED_ACCESS] —— 现在能编辑，退出应用之后才打不开。
 *
 * 这个判定故意留成纯逻辑（不 import Compose、不碰资源 id），好在 JVM 上直接断言：
 * 三个布尔输入共八种组合，光读代码看不出顺序对不对。
 */
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
