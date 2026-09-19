package com.marknote.app.ui.editor

import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.marknote.app.data.DocumentEncoding
import com.marknote.app.data.DocumentRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class EditorViewModel(
    private val repository: DocumentRepository,
    val uriString: String,
) : ViewModel() {

    private val uri: Uri = Uri.parse(uriString)

    var content by mutableStateOf(TextFieldValue(""))
        private set

    var isPreview by mutableStateOf(false)
        private set

    /**
     * 读盘成功、正文可信。
     *
     * false 有两种情形：还在读，或读取失败（无权限 / 文件已被移动删除）。两者都不该进入编辑/预览
     * —— 否则空正文会被写回原文件；[save] 与自动保存也据此直接返回。
     */
    var isLoaded by mutableStateOf(false)
        private set

    /** 只能读不能写（例如从文件管理器「打开方式」进来的只读授权），保存不会生效 */
    var readOnly by mutableStateOf(false)
        private set

    /** 最近一次保存是否失败（无写权限或文件已不在） */
    var saveFailed by mutableStateOf(false)
        private set

    /** 上次已落盘的内容，用于判断是否有未保存修改 */
    private var lastSavedText = ""

    /**
     * 本文档的编码：读取时探测出来，写回时沿用同一种。
     * 不能写死 UTF-8 —— 否则打开 GBK 文件会乱码，保存还会把原文件整体改写掉。
     */
    private var encoding = DocumentEncoding.UTF8

    /** 串行化写盘：防止连续编辑时多个保存协程并发写同一文件造成旧内容覆盖新内容 */
    private val saveMutex = Mutex()

    /**
     * 上次从磁盘读出正文的时刻（`elapsedRealtime`，0 表示本实例还没成功读过）。
     * 只给 [syncFromDiskIfClean] 做节流用。哨兵值不能取 `Long.MIN_VALUE`：
     * 第一次比较时 `now - Long.MIN_VALUE` 会溢出成负数，「刚读过」的判断就永远为真。
     */
    private var lastSyncAtMs = 0L

    /** 撤销栈。纯 Kotlin、不依赖 Android，所以它能在 JVM 上被断言实测（tools/run_checks.sh） */
    private val undoStack = UndoStack()

    /**
     * 有历史可撤 / 可重做。顶栏那两个按钮的禁用态绑的就是它们。
     *
     * 必须做成可观察状态，不能写成 `undoStack.canUndo` 的转发：撤销栈是普通对象，它内部
     * 那个 ArrayList 变化**不会通知 Compose**，按钮就会永远停在初始的 false
     * —— 编译通过、逻辑也对，只是按钮永远不变灰，很难看出来。
     * 所以每次动过栈都要手动同步一次（见 [refreshUndoState]）。
     */
    var canUndo by mutableStateOf(false)
        private set

    var canRedo by mutableStateOf(false)
        private set

    /** 动过撤销栈之后调用，把它的状态搬进可观察字段 */
    private fun refreshUndoState() {
        canUndo = undoStack.canUndo
        canRedo = undoStack.canRedo
    }

    /**
     * 记一步撤销。必须在 [content] 被覆盖**之前**调用：oldText 是这次改动之前的正文。
     *
     * [coalesce] 传 false 用于工具栏插入、替换这类**独立操作**——它们不该与之前的手打输入
     * 并成一步，否则撤销一次会连带退掉上一段输入。
     *
     * 时间戳用 `elapsedRealtime()` 而不是 `currentTimeMillis()`：后者会被用户改系统时间拨动，
     * 拨回去就会让撤销栈的「停顿阈值」判断失真。
     */
    private fun recordEdit(oldText: String, newText: String, coalesce: Boolean = true) {
        undoStack.record(oldText, newText, SystemClock.elapsedRealtime(), coalesce)
        refreshUndoState()
    }

    /** 历史作废（正文来源换了）时清栈，并同步按钮状态 */
    private fun clearUndoHistory() {
        undoStack.clear()
        refreshUndoState()
    }

    init {
        load()
    }

    /**
     * 读取文档。read 返回 null 表示读取失败（权限失效/文件不存在），
     * 此时不进入可编辑状态，避免把空内容当成文档正文回写覆盖原文件。
     */
    fun load() {
        viewModelScope.launch {
            val document = repository.readDocument(uri)
            if (document == null) {
                isLoaded = false
                return@launch
            }
            encoding = document.encoding
            lastSavedText = document.text
            content = TextFieldValue(document.text, TextRange(document.text.length))
            clearUndoHistory() // 刚打开（或重新载入）的文档没有编辑历史可撤
            isLoaded = true
            saveFailed = false
            readOnly = !repository.canWrite(uri)
            lastSyncAtMs = SystemClock.elapsedRealtime()
        }
    }

    /**
     * 编辑器重新进入组合时调用：只要没有未保存的改动，就重新读一次盘。
     *
     * 为什么需要：ViewModel 挂在 Activity 的 ViewModelStore 上，关掉编辑器并不会销毁它，
     * 重新打开同一份文档会命中同一个实例（init 里的 load 只跑过一次）。若文件在这期间被
     * 别的应用或同步工具改过，编辑器会一直显示旧内容，而用户只要再敲一个字，自动保存就会
     * 把整篇旧文本写回去，外部改动被静默覆盖。这里补一次同步。
     *
     * 有未保存改动（或读取失败）时一律不覆盖，宁可让用户看到自己没存下的内容。
     */
    fun syncFromDiskIfClean() {
        if (!isLoaded || hasUnsavedChanges) return
        // 节流：旋转、进出分屏、切语言/主题都会重建 Activity 并重新进入组合，每次重入都会走到这里，
        // 而一次同步就是一次**全量读盘**（大文件上是可感的卡顿）。刚读过就不必再读 —— 那点时间里
        // 外部应用改不了什么，而用户自己的自动保存本来就落在 800ms 这个量级上，仍远在窗口之内。
        val now = SystemClock.elapsedRealtime()
        if (now - lastSyncAtMs < SYNC_THROTTLE_MS) return
        lastSyncAtMs = now
        // 记下发起时的正文。readDocument 会挂起（跨进程读盘），这期间用户完全可能已经开始输入；
        // 不核对就恢复的话，会把刚敲进去的字整份替换成磁盘内容——静默丢字。
        val snapshot = content.text
        viewModelScope.launch {
            val document = repository.readDocument(uri) ?: return@launch
            // 读盘期间正文被动过（输入 / 撤销 / 替换）：放弃这次同步，用户的东西优先
            if (content.text != snapshot) return@launch
            // 磁盘上还是同一份内容：什么都不动，避免打断光标与滚动位置
            if (document.text == snapshot) return@launch
            encoding = document.encoding
            lastSavedText = document.text
            val cursor = content.selection.start.coerceAtMost(document.text.length)
            content = TextFieldValue(document.text, TextRange(cursor))
            // 正文被整份换掉了，栈里记的「某处曾经是某内容」全部失效。虽然栈在撤销前会
            // 自校验、对不上就自己清空，但那只在差分真的错位时才会触发——新旧文本若有一段
            // 共同前缀让差分恰好匹配上，就会按错误的位置改文本。显式清掉才安全。
            clearUndoHistory()
        }
    }

    fun onContentChange(newValue: TextFieldValue) {
        // 「回车续列表」要在**记录撤销之前**做：新行上的标记与这次回车算同一步，
        // 否则撤销一次只退掉标记、留下一个空行（与工具栏插入「各自单独成步」的取舍不同：
        // 那一步是用户的独立意图，这里只是同一次回车的一部分）
        val adjusted = continueListIfJustEntered(newValue) ?: newValue
        // 必须在 content 被覆盖之前记录。只挪光标时两段文本相同，栈会自己忽略。
        recordEdit(content.text, adjusted.text)
        content = adjusted
    }

    /**
     * 刚按下回车时按上一行的写法续上列表标记；其余情况返回 null（不改动输入）。
     *
     * 判据必须**严**：误判会把正常输入改坏，所以要求长度正好 +1、多出来的那一位是 `\n`、
     * 光标正落在它后面，并且**删掉它以后与原文逐字相同**（最后一条顺带挡掉「粘贴了含换行的
     * 内容」这类看起来相似、其实完全不同的变更）。
     */
    private fun continueListIfJustEntered(newValue: TextFieldValue): TextFieldValue? {
        val old = content.text
        val new = newValue.text
        if (new.length != old.length + 1) return null
        val selection = newValue.selection
        if (!selection.collapsed) return null
        val at = selection.start - 1
        if (at < 0 || at >= new.length || new[at] != '\n') return null
        if (!old.regionMatches(0, new, 0, at)) return null
        if (!old.regionMatches(at, new, at + 1, old.length - at)) return null
        val edit = continueListOnNewline(new, at + 1) ?: return null
        return TextFieldValue(edit.text, TextRange(edit.selectionStart, edit.selectionEnd))
    }

    /** 应用一次编辑行为（Tab 缩进/反缩进）：算一步撤销，与普通输入同级 */
    internal fun applyEdit(edit: EditResult) {
        recordEdit(content.text, edit.text, coalesce = false)
        content = TextFieldValue(edit.text, TextRange(edit.selectionStart, edit.selectionEnd))
    }

    /**
     * 撤销一步。没有历史可撤、或历史已与正文不符（栈会自己清空）时什么都不做。
     *
     * 光标跟着回到改动处：被撤销的内容常常在视野之外，看不见的撤销等于没发生。
     */
    fun undo() {
        val outcome = undoStack.undo(content.text)
        refreshUndoState()
        if (outcome != null) applyOutcome(outcome)
    }

    /** 重做一步。见 [undo] */
    fun redo() {
        val outcome = undoStack.redo(content.text)
        refreshUndoState()
        if (outcome != null) applyOutcome(outcome)
    }

    /** 应用撤销/重做的结果：换正文并把光标放到改动处，TextField 会自动滚动到光标可见 */
    private fun applyOutcome(outcome: UndoOutcome) {
        val caret = outcome.caret.coerceIn(0, outcome.text.length)
        content = TextFieldValue(outcome.text, TextRange(caret))
    }

    /** 大纲跳转：把光标移到指定偏移，TextField 会自动滚动到光标可见 */
    fun jumpTo(offset: Int) {
        val safe = offset.coerceIn(0, content.text.length)
        content = content.copy(selection = TextRange(safe))
    }

    /** 选中一段文本（搜索命中高亮），TextField 会自动滚动到选区可见 */
    fun selectRange(start: Int, end: Int) {
        val s = start.coerceIn(0, content.text.length)
        val e = end.coerceIn(s, content.text.length)
        content = content.copy(selection = TextRange(s, e))
    }

    /** 替换指定区间文本，光标落在替换内容之后 */
    fun replaceInRange(start: Int, end: Int, newText: String) {
        val s = start.coerceIn(0, content.text.length)
        val e = end.coerceIn(s, content.text.length)
        val replaced = content.text.replaceRange(s, e, newText)
        // 单处替换是一次独立意图，不与之前的手打输入合并
        recordEdit(content.text, replaced, coalesce = false)
        content = TextFieldValue(replaced, TextRange(s + newText.length))
    }

    /**
     * 全部替换（字面量匹配），返回替换次数。
     *
     * 计数按**非重叠**推进，与 `String.replace` 的实际行为一致：否则 `"aaaa"` 里替换
     * `"aa"` 会被算成 3 次，而实际只替换了 2 处。
     */
    fun replaceAll(query: String, replacement: String): Int {
        val text = content.text
        if (query.isEmpty()) return 0
        var count = 0
        var i = text.indexOf(query)
        while (i >= 0) {
            count++
            i = text.indexOf(query, i + query.length)
        }
        if (count > 0) {
            val newText = text.replace(query, replacement)
            // 替换全部是一次独立操作，不与之前的手打输入合并
            recordEdit(text, newText, coalesce = false)
            content = TextFieldValue(newText, TextRange(content.selection.start.coerceAtMost(newText.length)))
        }
        return count
    }

    /** 统计：非空白字符数 / 行数 */
    fun stats(): Pair<Int, Int> {
        val chars = content.text.count { !it.isWhitespace() }
        val lines = if (content.text.isEmpty()) 0 else content.text.count { it == '\n' } + 1
        return chars to lines
    }

    fun togglePreview() {
        if (!isPreview) save() // 切到预览前先落盘
        isPreview = !isPreview
    }

    val hasUnsavedChanges: Boolean
        get() = content.text != lastSavedText

    fun save() {
        // 读取失败时绝不能写：否则会把空白内容覆盖到原文件上
        if (!isLoaded) return
        if (content.text == lastSavedText) return
        viewModelScope.launch {
            saveMutex.withLock {
                // 锁内重新取一次内容：等锁期间用户可能又改了，如果照搬进入 save() 时捕获的
                // 快照，先发起的保存后拿到锁时就会用旧文本覆盖新文本（并把旧文本标成
                // 已保存，磁盘内容与编辑器长期不一致）。
                val text = content.text
                if (text == lastSavedText) return@withLock
                if (repository.saveDocument(uri, text, encoding)) {
                    lastSavedText = text
                    saveFailed = false
                    readOnly = false
                } else {
                    // 写盘失败（无写权限 / 文件已不在）：保留未保存状态并提示用户
                    saveFailed = true
                }
            }
        }
    }

    /** 应用一个工具栏插入动作：有选区则包裹选区，无选区则插入占位文本 */
    internal fun applyAction(action: MarkdownAction) {
        val current = content
        val sel = current.selection
        val selected = current.text.substring(sel.min, sel.max)

        val insert = action.prefix + selected.ifEmpty { action.placeholder } + action.suffix
        val newText = current.text.replaceRange(sel.min, sel.max, insert)

        // 光标落在插入内容内部，方便继续输入
        val cursor = if (selected.isEmpty() && action.placeholder.isNotEmpty()) {
            sel.min + action.prefix.length + action.placeholder.length
        } else {
            sel.min + insert.length
        }
        // 工具栏插入是一次独立意图：不合并，撤销一次就干净退回插入前
        recordEdit(current.text, newText, coalesce = false)
        content = TextFieldValue(newText, TextRange(cursor))
    }

    private companion object {
        /** [syncFromDiskIfClean] 的节流窗口：窗口内刚读过盘就不再重读 */
        const val SYNC_THROTTLE_MS = 2_000L
    }
}
