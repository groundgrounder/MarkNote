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

    /** 只用于写（发系统广播/服务调用），持有 Application 而非 Activity，避免长生命周期引用泄漏 */
    private val appContext = context.applicationContext

    private val prefs = context.getSharedPreferences(AppLocaleStore.PREFS, Context.MODE_PRIVATE)

    /** 主题模式：跟随系统 / 浅色 / 深色 */
    var themeMode by mutableStateOf(
        runCatching {
            ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
        }.getOrDefault(ThemeMode.SYSTEM),
    )
        private set

    /**
     * 界面语言。改动后需要重建 Activity 才会生效（见 SettingsScreen 里的切语言回调）：
     * 语言是在 attachBaseContext 阶段套到 Context 上的，运行中改不了已经用出去的 Resources。
     *
     * 初值走 AppLocaleStore.current：Android 13+ 用户在系统「应用语言」里改过时，
     * 应用内的选择器要显示同一种语言，不能只认自己的 SharedPreferences。
     */
    var appLanguage by mutableStateOf(AppLocaleStore.current(context))
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

    /**
     * 写入语言偏好。走 AppLocaleStore 写，它会同时更新进程内缓存、SharedPreferences，
     * 以及在 Android 13+ 同步到系统的「按应用语言」——三处不一致就会出现
     * 「应用内显示法语、系统设置里显示英语」这种分裂。
     */
    fun updateAppLanguage(language: AppLanguage) {
        appLanguage = language
        AppLocaleStore.write(appContext, language)
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
