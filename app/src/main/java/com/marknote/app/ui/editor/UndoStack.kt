package com.marknote.app.ui.editor

/**
 * 一次文本改动：在 [start] 处把 [removed] 换成 [inserted]。两者都可以为空
 * （空 [removed] = 纯插入，空 [inserted] = 纯删除）。
 *
 * 存**差分**而不是整份快照，是因为快照栈的代价是「步数 × 文本长度」：一份 20 万字符的
 * 文档存 100 步就是 2000 万字符，而这里的每次按键往往只动一个字符。
 */
data class TextEdit(
    val start: Int,
    val removed: String,
    val inserted: String,
) {
    /** 执行之后光标应落在这里（插进来的内容末尾） */
    val caretAfter: Int get() = start + inserted.length

    /** 撤销之后光标应落在这里（被恢复的内容末尾） */
    val caretBefore: Int get() = start + removed.length
}

/** 撤销或重做的结果：新文本与光标位置 */
data class UndoOutcome(val text: String, val caret: Int)

/**
 * 撤销栈。纯 Kotlin、不依赖 Android，可以直接用 JVM 断言实测。
 *
 * ## 为什么必须合并连续的按键
 *
 * 不合并的话，打一句 20 个字的话就要按 20 次撤销才能退回去——用户期望的是「撤销这句话」。
 * 所以相邻的同类改动（连续输入、连续退格）会被并成一个条目。
 *
 * 合并只认**纯插入接纯插入**、**纯删除接纯删除**这两种，且位置必须首尾相接。
 * 「删一个字再打一个字」不合并：那是两次独立的意图，合并之后撤销一次会跳过中间状态。
 *
 * 时间也参与判断：隔了 [coalesceMs] 以上就不合并。人打字时会有思考停顿，
 * 停顿之后的新内容应当单独成步，否则撤销一次会退掉一大段。
 *
 * ## 为什么用两个栈而不是一个栈加指针
 *
 * 两个栈天然表达「一旦产生新改动，重做分支就作废」：新改动进来时清空重做栈即可。
 * 单栈加指针则要在插入时截断指针之后的尾巴，容易漏。
 *
 * ## 为什么撤销前要校验
 *
 * 栈里记得是「某处曾经是这个内容」，而重开文档重新读盘（[EditorViewModel.syncFromDiskIfClean]）
 * 这类操作会直接替换正文，让这份记忆失效。此时按差分硬改会**静默损坏文本**（错位删除、截断）。
 * 所以撤销/重做前先核对 [TextEdit] 与当前文本是否还对得上，对不上就把整个栈清掉——
 * 历史已经不可信，留着只会继续出错。
 *
 * ## 上限
 *
 * 条目数与总字符数双重限制，超了从**最老**那一头淘汰。只限条目数是不够的：
 * 一次粘贴几万字符就能让 100 个条目占掉几十 MB。
 */
class UndoStack @JvmOverloads constructor(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxChars: Int = DEFAULT_MAX_CHARS,
    private val coalesceMs: Long = DEFAULT_COALESCE_MS,
) {

    private val undoList = ArrayList<TextEdit>()
    private val redoList = ArrayList<TextEdit>()

    /** 已入栈内容的字符总量，用于上限判断。只统计差分，不是文档长度。 */
    private var totalChars = 0

    /** 上一条改动入栈的时刻。撤销/重做之后会重置，免得与之后的输入意外合并。 */
    private var lastAtMs = Long.MIN_VALUE

    val canUndo: Boolean get() = undoList.isNotEmpty()

    val canRedo: Boolean get() = redoList.isNotEmpty()

    /** 撤销步数。界面上用不到，但断言里要确认合并真的发生了 */
    val undoDepth: Int get() = undoList.size

    fun clear() {
        undoList.clear()
        redoList.clear()
        totalChars = 0
        lastAtMs = Long.MIN_VALUE
    }

    /**
     * 记录一次从 [oldText] 到 [newText] 的变化。文本没变则什么都不做。
     *
     * [nowMs] 由调用方传入而不是在这里取时间：本类不许依赖 Android，
     * 而 `SystemClock.elapsedRealtime()` 正好是调用方该给的单调时钟（不受用户改表影响）。
     *
     * [coalesce] 传 false 用于「工具栏插入」「替换全部」这类**独立操作**——它们不该与之前的
     * 手打输入并成一步，否则撤销一次会连带上一段输入。
     */
    @JvmOverloads
    fun record(oldText: String, newText: String, nowMs: Long, coalesce: Boolean = true) {
        if (oldText == newText) return
        val edit = diff(oldText, newText)

        // 产生新改动的那一刻，重做分支就不成立了
        redoList.clear()

        // lastAtMs 为 MIN_VALUE 表示「上一步是撤销/重做或栈刚清空」——那种情况下不合并，
        // 否则撤销之后紧接着输入的内容会被并进刚撤掉的那一步里。
        // 也要先判它再相减：MIN_VALUE 参与减法会溢出成负数，反而满足「在阈值内」。
        val inBurst = coalesce && lastAtMs != Long.MIN_VALUE && nowMs - lastAtMs <= coalesceMs
        val prev = if (inBurst) undoList.lastOrNull() else null
        if (prev != null) {
            val merged = tryMerge(prev, edit)
            if (merged != null) {
                totalChars -= prev.removed.length + prev.inserted.length
                undoList[undoList.size - 1] = merged
                totalChars += merged.removed.length + merged.inserted.length
                lastAtMs = nowMs
                trim()
                return
            }
        }
        undoList.add(edit)
        totalChars += edit.removed.length + edit.inserted.length
        lastAtMs = nowMs
        trim()
    }

    /** 撤销一步。返回新文本与光标；无步可撤、或历史已与文本不符（此时会清空栈）返回 null。 */
    fun undo(text: String): UndoOutcome? {
        val edit = undoList.lastOrNull() ?: return null
        // 撤销是把 inserted 换回 removed，所以要核对当前文本里确实躺着 inserted
        if (!matches(text, edit, forward = false)) {
            clear()
            return null
        }
        undoList.removeAt(undoList.size - 1)
        totalChars -= edit.removed.length + edit.inserted.length
        redoList.add(edit)
        lastAtMs = Long.MIN_VALUE
        return UndoOutcome(apply(text, edit, forward = false), edit.caretBefore)
    }

    /** 重做一步。返回新文本与光标；无可重做、或历史已与文本不符（此时会清空栈）返回 null。 */
    fun redo(text: String): UndoOutcome? {
        val edit = redoList.lastOrNull() ?: return null
        if (!matches(text, edit, forward = true)) {
            clear()
            return null
        }
        redoList.removeAt(redoList.size - 1)
        totalChars += edit.removed.length + edit.inserted.length
        undoList.add(edit)
        lastAtMs = Long.MIN_VALUE
        return UndoOutcome(apply(text, edit, forward = true), edit.caretAfter)
    }

    /** 从最老的一头淘汰，直到同时满足两个上限 */
    private fun trim() {
        while (undoList.isNotEmpty() && (undoList.size > maxEntries || totalChars > maxChars)) {
            val oldest = undoList.removeAt(0)
            totalChars -= oldest.removed.length + oldest.inserted.length
        }
    }

    companion object {

        const val DEFAULT_MAX_ENTRIES = 200

        /**
         * 差分内容的字符预算。约 1MB 内存（UTF-16）。
         *
         * MarkNote 不对正文体积设门槛（笔记通常几 KB），所以这个值是按「历史够用 + 内存
         * 可控」定的：200 个条目即使每条都塞满，也不会超过这个预算。
         */
        const val DEFAULT_MAX_CHARS = 500_000

        /** 打字节奏内的停顿阈值。超过它认为用户换了个想法，另起一步。 */
        const val DEFAULT_COALESCE_MS = 600L

        /**
         * 求两个字符串之间的**单段**差分。
         *
         * 用「公共前缀 + 公共后缀」夹出中间那段。它不保证是最小差分（比如 `abab` → `ababab`
         * 会算出插入 `ab` 而不是插入两次 `ab`），但单段差分正是撤销栈需要的形状：
         * 一个 (start, removed, inserted) 就能描述一次改动。
         *
         * 后缀长度要减掉已匹配的前缀，否则前后缀在短字符串上会重叠，算出负区间。
         */
        @JvmStatic
        fun diff(oldText: String, newText: String): TextEdit {
            var prefix = 0
            val maxPrefix = minOf(oldText.length, newText.length)
            while (prefix < maxPrefix && oldText[prefix] == newText[prefix]) prefix++

            var suffix = 0
            val maxSuffix = minOf(oldText.length, newText.length) - prefix
            while (suffix < maxSuffix &&
                oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
            ) suffix++

            return TextEdit(
                start = prefix,
                removed = oldText.substring(prefix, oldText.length - suffix),
                inserted = newText.substring(prefix, newText.length - suffix),
            )
        }

        /**
         * 应用一次差分。[forward] = 执行，否则 = 撤销。
         *
         * 调用方应当先用 [matches] 核对过；这里不重复校验，是为了让断言能直接测它本身。
         */
        @JvmStatic
        fun apply(text: String, edit: TextEdit, forward: Boolean): String {
            val from = if (forward) edit.removed else edit.inserted
            val to = if (forward) edit.inserted else edit.removed
            val at = edit.start
            return text.substring(0, at) + to + text.substring(at + from.length)
        }

        /**
         * 这条差分与当前文本还对得上吗。
         *
         * [forward] 为 true 时核对的是 [TextEdit.removed]（重做要把它换掉），
         * 为 false 时核对的是 [TextEdit.inserted]（撤销要把它换回去）。
         */
        @JvmStatic
        fun matches(text: String, edit: TextEdit, forward: Boolean): Boolean {
            val from = if (forward) edit.removed else edit.inserted
            val at = edit.start
            if (at < 0 || at > text.length) return false
            if (at + from.length > text.length) return false
            // 空段（纯插入 / 纯删除）只要位置合法就算对得上
            return from.isEmpty() || text.regionMatches(at, from, 0, from.length)
        }

        /**
         * 两条改动能否并成一条。
         *
         * 两种可并的情况都要求**首尾相接**，中间不能隔着别的内容：
         * - 纯插入接纯插入：新插入的位置正好在上一条插入内容的末尾；
         * - 纯删除接纯删除（连按退格）：新删掉的内容正好挨在上一条删除位置之前。
         */
        private fun tryMerge(prev: TextEdit, next: TextEdit): TextEdit? = when {
            prev.removed.isEmpty() && next.removed.isEmpty() &&
                next.start == prev.start + prev.inserted.length ->
                TextEdit(prev.start, "", prev.inserted + next.inserted)

            prev.inserted.isEmpty() && next.inserted.isEmpty() &&
                next.start + next.removed.length == prev.start ->
                TextEdit(next.start, next.removed + prev.removed, "")

            else -> null
        }
    }
}
