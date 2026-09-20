package com.marknote.app.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.marknote.app.R

/**
 * 「输入一个名字」的弹窗：新建文件夹与重命名共用。
 *
 * 两处都要「弹出来 → 打字 → 确定」，各写一份的话迟早分叉（一处记得把光标放到末尾、
 * 另一处忘了，改名字时用户得先点一下输入框才能接着打字）。
 *
 * [initial] 是进来的初始内容：重命名给原名，新建给空串。
 */
@Composable
internal fun NameInputDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    // 光标放末尾而不是全选：改名最常见的是「留着原名改一小段」，全选会让人一打字就把原名冲掉
    var value by remember(initial) {
        mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
    }
    val trimmed = value.text.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.name_field_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            // 空名字直接不可点：交给 provider 报错再翻译回来，不如在这里就拦住
            TextButton(
                onClick = { onConfirm(trimmed) },
                enabled = trimmed.isNotEmpty(),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
