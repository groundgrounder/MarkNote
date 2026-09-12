package com.marknote.app.ui.editor

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.HorizontalRule
import androidx.compose.material.icons.outlined.Link
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

val markdownActions = listOf(
    MarkdownAction("H1", "# ", placeholder = "标题"),
    MarkdownAction("H2", "## ", placeholder = "标题"),
    MarkdownAction("B", "**", "**", "加粗"),
    MarkdownAction("I", "*", "*", "斜体"),
    MarkdownAction("S", "~~", "~~", "删除线"),
    MarkdownAction("引用", "> ", placeholder = "引用", icon = Icons.Outlined.FormatQuote),
    MarkdownAction("列表", "- ", placeholder = "列表项", icon = Icons.AutoMirrored.Outlined.FormatListBulleted),
    MarkdownAction("链接", "[", "](https://)", "链接文字", icon = Icons.Outlined.Link),
    MarkdownAction("代码", "```\n", "\n```", "代码", icon = Icons.Outlined.Code),
    MarkdownAction("分割线", "\n---\n", icon = Icons.Outlined.HorizontalRule),
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
            val text = repository.read(uri)
            if (text == null) {
                isLoaded = false
                loadFailed = true
                return@launch
            }
            lastSavedText = text
            content = TextFieldValue(text, TextRange(text.length))
            isLoaded = true
            loadFailed = false
            saveFailed = false
            readOnly = !repository.canWrite(uri)
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

    /** 全部替换（字面量匹配），返回替换次数 */
    fun replaceAll(query: String, replacement: String): Int {
        if (query.isEmpty()) return 0
        var count = 0
        var i = content.text.indexOf(query)
        while (i >= 0) { count++; i = content.text.indexOf(query, i + 1) }
        if (count > 0) {
            val newText = content.text.replace(query, replacement)
            content = TextFieldValue(newText, TextRange(newText.length.coerceAtMost(content.selection.start)))
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
        val text = content.text
        if (text == lastSavedText) return
        viewModelScope.launch {
            saveMutex.withLock {
                if (repository.save(uri, text)) {
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
