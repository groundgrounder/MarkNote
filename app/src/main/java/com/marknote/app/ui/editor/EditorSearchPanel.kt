package com.marknote.app.ui.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.marknote.app.R

@Composable
internal fun SearchPanel(
    query: String,
    onQueryChange: (String) -> Unit,
    matchCase: Boolean,
    onToggleMatchCase: () -> Unit,
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
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    val matchCaseDesc = stringResource(R.string.match_case)
    val transparentFieldColors = TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
    )

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
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onNext() }),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    colors = transparentFieldColors,
                )
                Text(
                    text = if (query.isEmpty()) "" else "${if (matchCount == 0) 0 else matchIndex + 1}/$matchCount",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconToggleButton(
                    checked = matchCase,
                    onCheckedChange = { onToggleMatchCase() },
                    modifier = Modifier.semantics { contentDescription = matchCaseDesc },
                ) {
                    Text(
                        text = "Aa",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (matchCase) FontWeight.Bold else FontWeight.Normal,
                        color = if (matchCase) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
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

            if (onReplace != null && onReplaceAll != null) {
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
                        colors = transparentFieldColors,
                    )
                    TextButton(onClick = onReplace, enabled = matchCount > 0) {
                        Text(stringResource(R.string.replace))
                    }
                    TextButton(onClick = onReplaceAll, enabled = matchCount > 0) {
                        Text(stringResource(R.string.replace_all))
                    }
                }
            }
            HorizontalDivider()
        }
    }
}
