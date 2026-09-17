package com.marknote.app.ui.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.marknote.app.R

/**
 * 搜索 / 替换面板（从 EditorScreen.kt 拆出，2026-09-16）。
 *
 * 编辑器态与预览态共用，差异由**类型**表达而不是一个布尔开关：预览是只读视图，没有「替换」
 * 这回事，调用方不传 [onReplace] / [onReplaceAll] 就渲染不出替换那一行
 * —— 早先是传两个空 lambda + `showReplace = false`，读起来像「按钮在但点了没反应」。
 *
 * 命中数由调用方算好传进来：编辑器态在源文本上搜，预览态在**渲染结果**上搜
 * （两套偏移不同，见 MarkdownPreview 的说明）。
 */
@Composable
internal fun SearchPanel(
    query: String,
    onQueryChange: (String) -> Unit,
    matchCount: Int,
    matchIndex: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    replacement: String = "",
    onReplacementChange: (String) -> Unit = {},
    onReplace: (() -> Unit)? = null,
    onReplaceAll: (() -> Unit)? = null,
) {
    // 先落到局部 val：可空参数在 lambda 里才能被智能转换（Kotlin 对参数做 smart cast 有前提）
    val replace = onReplace
    val replaceAll = onReplaceAll

    Surface {
        Column {
            HorizontalDivider()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp),
            ) {
                TextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text(stringResource(R.string.search)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                Text(
                    text = if (query.isEmpty()) "" else "${if (matchCount == 0) 0 else matchIndex + 1}/$matchCount",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = onPrev, enabled = matchCount > 0) {
                    Icon(
                        Icons.Outlined.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.previous_match),
                    )
                }
                IconButton(onClick = onNext, enabled = matchCount > 0) {
                    Icon(
                        Icons.Outlined.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.next_match),
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.close_search),
                    )
                }
            }
            // 没传替换回调 = 只读视图（预览），不渲染这一行
            if (replace != null && replaceAll != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    TextField(
                        value = replacement,
                        onValueChange = onReplacementChange,
                        placeholder = { Text(stringResource(R.string.replace_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                    TextButton(onClick = replace, enabled = matchCount > 0) {
                        Text(stringResource(R.string.replace))
                    }
                    TextButton(onClick = replaceAll, enabled = matchCount > 0) {
                        Text(stringResource(R.string.replace_all))
                    }
                }
            }
            HorizontalDivider()
        }
    }
}
