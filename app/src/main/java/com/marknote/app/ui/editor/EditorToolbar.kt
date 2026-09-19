package com.marknote.app.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.HorizontalRule
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.marknote.app.R

/** 工具栏上的一种 Markdown 插入动作。icon 非空时工具栏显示图标，否则显示 label 文字 */
internal data class MarkdownAction(
    val label: String,
    val prefix: String,
    val suffix: String = "",
    val placeholder: String = "",
    val icon: ImageVector? = null,
)

/**
 * 工具栏动作列表。
 *
 * 与 [MarkdownToolbar] 同文件：这三样（动作定义 / 动作表 / 渲染它的工具栏）改一处就要一起改，
 * 原先动作表放在 EditorViewModel.kt 里，改个按钮文案得跨文件找（2026-09-16 移到本文件）。
 *
 * 做成 @Composable 而不是顶层常量，是因为 label（图标按钮的无障碍描述）与 placeholder
 * （无选区时插入的占位文字）都要跟随界面语言；H1/H2/B/I/S 这类符号本身是语言无关的，
 * 保持原样输出成 Markdown 语法。
 */
@Composable
private fun markdownActions(): List<MarkdownAction> = listOf(
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

/**
 * 按 label 取同一个工具栏动作（键盘快捷键用）。
 *
 * 走**同一个列表**，这样「Ctrl+B」与点工具栏上的 B 得到的插入结果逐字一致；
 * 两处各写一份前缀，迟早会分叉（工具栏那句占位文案还跟着界面语言变）。
 */
@Composable
internal fun markdownActionByLabel(label: String): MarkdownAction? =
    markdownActions().firstOrNull { it.label == label }

@Composable
internal fun MarkdownToolbar(
    onAction: (MarkdownAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth()) {
        Column {
            HorizontalDivider()
            // 动作文案随界面语言变化，在 composable 作用域内现取
            val actions = markdownActions()
            LazyRow(
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(actions) { action ->
                    IconButton(onClick = { onAction(action) }) {
                        if (action.icon != null) {
                            Icon(
                                action.icon,
                                contentDescription = action.label,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                text = action.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
