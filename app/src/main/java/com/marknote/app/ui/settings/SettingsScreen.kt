package com.marknote.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.marknote.app.BuildConfig
import com.marknote.app.R
import com.marknote.app.data.AppLanguage
import com.marknote.app.data.SettingsRepository
import com.marknote.app.data.ThemeMode
import com.marknote.app.ui.common.findActivity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: SettingsRepository,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }

    var showLanguagePicker by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
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
            SectionLabel(stringResource(R.string.appearance))
            SettingGroup(label = stringResource(R.string.theme_mode)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val items = listOf(
                        ThemeMode.SYSTEM to stringResource(R.string.theme_system),
                        ThemeMode.LIGHT to stringResource(R.string.theme_light),
                        ThemeMode.DARK to stringResource(R.string.theme_dark),
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

            SectionLabel(stringResource(R.string.language_section))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { showLanguagePicker = true }
                    .padding(vertical = 8.dp),
            ) {
                Icon(
                    Icons.Outlined.Language,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.language), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        // 跟随系统时直接说明「用的是系统语言」，比只显示「跟随系统」更清楚
                        text = if (settings.appLanguage == AppLanguage.SYSTEM) {
                            stringResource(R.string.language_system_detail)
                        } else {
                            settings.appLanguage.endonym
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionLabel(stringResource(R.string.editor_section))
            SettingGroup(label = stringResource(R.string.editor_font_size, settings.editorFontSp)) {
                FontSizePicker(
                    current = settings.editorFontSp,
                    onSelect = settings::updateEditorFont,
                )
            }

            SectionLabel(stringResource(R.string.preview_section))
            SettingGroup(label = stringResource(R.string.preview_font_size, settings.previewFontSp)) {
                FontSizePicker(
                    current = settings.previewFontSp,
                    onSelect = settings::updatePreviewFont,
                )
            }

            SectionLabel(stringResource(R.string.save_section))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.auto_save), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = stringResource(
                            if (settings.autoSave) R.string.auto_save_on else R.string.auto_save_off,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.autoSave,
                    onCheckedChange = settings::updateAutoSave,
                )
            }

            SectionLabel(stringResource(R.string.about))
            Text(
                "MarkNote v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showLanguagePicker) {
        LanguagePickerDialog(
            current = settings.appLanguage,
            onDismiss = { showLanguagePicker = false },
            onSelect = { language ->
                showLanguagePicker = false
                if (settings.appLanguage != language) {
                    settings.updateAppLanguage(language)
                    // 语言是在 attachBaseContext 阶段套到 Context 上的，运行中改不了已经用出去的
                    // Resources；重建 Activity 后新语言才会整体生效（rememberSaveable 会保住
                    // 当前停留在设置页、以及正在编辑的文档）。
                    // Android 13+ 还会把选择同步给系统「按应用语言」，系统那侧可能也重建一次；
                    // 重复重建只是多闪一下，换来的是任何系统版本上都立刻生效。
                    context.findActivity()?.recreate()
                }
            },
        )
    }
}

/** 语言单选弹窗：每种语言用**自称**展示，任何界面语言下都能一眼认出自己的语言 */
@Composable
private fun LanguagePickerDialog(
    current: AppLanguage,
    onDismiss: () -> Unit,
    onSelect: (AppLanguage) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.language)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                AppLanguage.entries.forEach { language ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = current == language,
                                role = Role.RadioButton,
                                onClick = { onSelect(language) },
                            )
                            .padding(vertical = 10.dp),
                    ) {
                        RadioButton(selected = current == language, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = if (language == AppLanguage.SYSTEM) {
                                stringResource(R.string.language_system)
                            } else {
                                language.endonym
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
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
    val sizes = listOf(
        14 to stringResource(R.string.font_small),
        16 to stringResource(R.string.font_standard),
        20 to stringResource(R.string.font_large),
    )
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
