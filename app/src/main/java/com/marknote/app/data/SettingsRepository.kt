package com.marknote.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * 应用设置：SharedPreferences 持久化 + Compose 可观察状态。
 * 字段修改即落盘，UI 通过读字段自动重组。
 */
class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 主题模式：跟随系统 / 浅色 / 深色 */
    var themeMode by mutableStateOf(
        runCatching {
            ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
        }.getOrDefault(ThemeMode.SYSTEM),
    )
        private set

    /** 编辑器字号（sp） */
    var editorFontSp by mutableIntStateOf(prefs.getInt(KEY_EDITOR_FONT, 16))
        private set

    /** 预览字号（sp） */
    var previewFontSp by mutableIntStateOf(prefs.getInt(KEY_PREVIEW_FONT, 16))
        private set

    /** 自动保存；关闭后编辑器顶栏出现手动保存按钮 */
    var autoSave by mutableStateOf(prefs.getBoolean(KEY_AUTO_SAVE, true))
        private set

    fun updateThemeMode(mode: ThemeMode) {
        themeMode = mode
        prefs.edit().putString(KEY_THEME, mode.name).apply()
    }

    fun updateEditorFont(sp: Int) {
        editorFontSp = sp
        prefs.edit().putInt(KEY_EDITOR_FONT, sp).apply()
    }

    fun updatePreviewFont(sp: Int) {
        previewFontSp = sp
        prefs.edit().putInt(KEY_PREVIEW_FONT, sp).apply()
    }

    fun updateAutoSave(enabled: Boolean) {
        autoSave = enabled
        prefs.edit().putBoolean(KEY_AUTO_SAVE, enabled).apply()
    }

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_EDITOR_FONT = "editor_font_sp"
        const val KEY_PREVIEW_FONT = "preview_font_sp"
        const val KEY_AUTO_SAVE = "auto_save"
    }
}
