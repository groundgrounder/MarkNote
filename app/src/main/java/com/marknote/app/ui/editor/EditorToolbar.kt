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

internal data class MarkdownAction(
    val label: String,
    val prefix: String,
    val suffix: String = "",
    val placeholder: String = "",
    val icon: ImageVector? = null,
)

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
