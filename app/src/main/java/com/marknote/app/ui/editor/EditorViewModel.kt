package com.marknote.app.ui.editor

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.HorizontalRule
import androidx.compose.material.icons.outlined.Link
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.marknote.app.R
import com.marknote.app.data.DocumentEncoding
import com.marknote.app.data.DocumentRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 工具栏上的一种 Markdown 插入动作。icon 非空时工具栏显示图标，否则显示 label 文字 */
data class MarkdownAction(
    val label: String,
    val prefix: String,
    val suffix: String = "",
    val placeholder: String = "",
    val icon: ImageVector? = null,
)

/** 一个大纲条目：标题层级、文字、在全文中的字符偏移 */
data class Heading(val level: Int, val title: String, val offset: Int)

/** 从 Markdown 全文解析标题大纲（跳过代码块内部） */
fun parseOutline(text: String): List<Heading> {
    val result = mutableListOf<Heading>()
    val headingRegex = Regex("^(#{1,6})\\s+(.+?)\\s*$")
    var offset = 0
    var inCodeBlock = false
    for (line in text.split("\n")) {
        if (line.trimStart().startsWith("```")) {
            inCodeBlock = !inCodeBlock
        } else if (!inCodeBlock) {
            val m = headingRegex.matchEntire(line)
            if (m != null) {
                result.add(Heading(m.groupValues[1].length, m.groupValues[2], offset))
            }
        }
        offset += line.length + 1
    }
    return result
}

/**
 * 工具栏动作列表。
 *
 * 做成 @Composable 而不是顶层常量，是因为 label（图标按钮的无障碍描述）与 placeholder
 * （无选区时插入的占位文字）都要跟随界面语言；H1/H2/B/I/S 这类符号本身是语言无关的，
 * 保持原样输出成 Markdown 语法。
 */
@Composable
fun markdownActions(): List<MarkdownAction> = listOf(
    MarkdownAction("H1", "# ", placeholder = stringResource(R.string.md_heading)),
    MarkdownAction("H2", "## ", placeholder = stringResource(R.string.md_heading)),
    MarkdownAction("B", "**", "**", stringResource(R.string.md_bold)),
    MarkdownAction("I", "*", "*", stringResource(R.string.md_italic)),
    MarkdownAction("S", "~~", "~~", stringResource(R.string.md_strikethrough)),
    MarkdownAction(
        stringResource(R.string.md_quote), "> ",
        placeholder = stringResource(R.string.md_quote),
        icon = Icons.Outlined.FormatQuote,
    ),
    MarkdownAction(
        stringResource(R.string.md_list), "- ",
        placeholder = stringResource(R.string.md_list_item),
        icon = Icons.AutoMirrored.Outlined.FormatListBulleted,
    ),
    MarkdownAction(
        stringResource(R.string.md_link), "[", "](https://)",
        stringResource(R.string.md_link_text),
        icon = Icons.Outlined.Link,
    ),
    MarkdownAction(
        stringResource(R.string.md_code), "```\n", "\n```",
        stringResource(R.string.md_code),
        icon = Icons.Outlined.Code,
    ),
    MarkdownAction(
        stringResource(R.string.md_divider), "\n---\n",
        icon = Icons.Outlined.HorizontalRule,
    ),
)

class EditorViewModel(
    private val repository: DocumentRepository,
    val uriString: String,
) : ViewModel() {

    private val uri: Uri = Uri.parse(uriString)

    var content by mutableStateOf(TextFieldValue(""))
        private set

    var isPreview by mutableStateOf(false)
        private set

    var isLoaded by mutableStateOf(false)
        private set

    /** 读取失败（无权限 / 文件已被移动删除）：编辑器显示错误态，而不是伪装成空文档 */
    var loadFailed by mutableStateOf(false)
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
                loadFailed = true
                return@launch
            }
            encoding = document.encoding
            lastSavedText = document.text
            content = TextFieldValue(document.text, TextRange(document.text.length))
            isLoaded = true
            loadFailed = false
            saveFailed = false
            readOnly = !repository.canWrite(uri)
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
        viewModelScope.launch {
            val document = repository.readDocument(uri) ?: return@launch
            // 磁盘上还是同一份内容：什么都不动，避免打断光标与滚动位置
            if (document.text == content.text) return@launch
            encoding = document.encoding
            lastSavedText = document.text
            val cursor = content.selection.start.coerceAtMost(document.text.length)
            content = TextFieldValue(document.text, TextRange(cursor))
        }
    }

    fun onContentChange(newValue: TextFieldValue) {
        content = newValue
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
    fun applyAction(action: MarkdownAction) {
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
        content = TextFieldValue(newText, TextRange(cursor))
    }
}
