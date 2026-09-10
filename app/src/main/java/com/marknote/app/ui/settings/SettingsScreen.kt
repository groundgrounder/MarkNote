package com.marknote.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.marknote.app.BuildConfig
import com.marknote.app.data.SettingsRepository
import com.marknote.app.data.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: SettingsRepository,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
        ) {
            SectionLabel("外观")
            SettingGroup(label = "主题模式") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val items = listOf(
                        ThemeMode.SYSTEM to "跟随系统",
                        ThemeMode.LIGHT to "浅色",
                        ThemeMode.DARK to "深色",
                    )
                    items.forEachIndexed { index, (mode, label) ->
                        SegmentedButton(
                            selected = settings.themeMode == mode,
                            onClick = { settings.updateThemeMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index, count = items.size,
                            ),
                        ) { Text(label) }
                    }
                }
            }

            SectionLabel("编辑器")
            SettingGroup(label = "编辑器字号（当前 ${settings.editorFontSp}sp）") {
                FontSizePicker(
                    current = settings.editorFontSp,
                    onSelect = settings::updateEditorFont,
                )
            }

            SectionLabel("预览")
            SettingGroup(label = "预览字号（当前 ${settings.previewFontSp}sp）") {
                FontSizePicker(
                    current = settings.previewFontSp,
                    onSelect = settings::updatePreviewFont,
                )
            }

            SectionLabel("保存")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("自动保存", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (settings.autoSave) "输入停顿 800ms 自动写回文件"
                        else "关闭后顶栏出现保存按钮，需手动保存",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.autoSave,
                    onCheckedChange = settings::updateAutoSave,
                )
            }

            SectionLabel("关于")
            Text(
                "MarkNote v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingGroup(
    label: String,
    content: @Composable () -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun FontSizePicker(
    current: Int,
    onSelect: (Int) -> Unit,
) {
    val sizes = listOf(14 to "小", 16 to "标准", 20 to "大")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        sizes.forEachIndexed { index, (sp, label) ->
            SegmentedButton(
                selected = current == sp,
                onClick = { onSelect(sp) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = sizes.size),
            ) { Text(label) }
        }
    }
}
