package com.marknote.app.data

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ThemeMode { SYSTEM, LIGHT, DARK }

object AppThemeStore {

    const val KEY = "theme_mode"

    fun forcedDark(context: Context): Boolean? = when (
        runCatching {
            ThemeMode.valueOf(
                context.getSharedPreferences(AppLocaleStore.PREFS, Context.MODE_PRIVATE)
                    .getString(KEY, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name,
            )
        }.getOrDefault(ThemeMode.SYSTEM)
    ) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> null
    }
}

class SettingsRepository(context: Context) {

    private val appContext = context.applicationContext

    private val prefs = context.getSharedPreferences(AppLocaleStore.PREFS, Context.MODE_PRIVATE)

    var themeMode by mutableStateOf(
        runCatching {
            ThemeMode.valueOf(prefs.getString(AppThemeStore.KEY, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
        }.getOrDefault(ThemeMode.SYSTEM),
    )
        private set

    var appLanguage by mutableStateOf(AppLocaleStore.current(context))
        private set

    var editorFontSp by mutableIntStateOf(prefs.getInt(KEY_EDITOR_FONT, 16))
        private set

    var previewFontSp by mutableIntStateOf(prefs.getInt(KEY_PREVIEW_FONT, 16))
        private set

    var autoSave by mutableStateOf(prefs.getBoolean(KEY_AUTO_SAVE, true))
        private set

    var folderShowHiddenFiles by mutableStateOf(prefs.getBoolean(KEY_FOLDER_SHOW_HIDDEN, false))
        private set

    var showNoAccessNotice by mutableStateOf(prefs.getBoolean(KEY_SHOW_NO_ACCESS_NOTICE, true))
        private set

    fun updateThemeMode(mode: ThemeMode) {
        themeMode = mode
        prefs.edit().putString(AppThemeStore.KEY, mode.name).apply()
    }

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

    fun updateFolderShowHiddenFiles(show: Boolean) {
        folderShowHiddenFiles = show
        prefs.edit().putBoolean(KEY_FOLDER_SHOW_HIDDEN, show).apply()
    }

    fun updateShowNoAccessNotice(show: Boolean) {
        showNoAccessNotice = show
        prefs.edit().putBoolean(KEY_SHOW_NO_ACCESS_NOTICE, show).apply()
    }

    private companion object {
        const val KEY_EDITOR_FONT = "editor_font_sp"
        const val KEY_PREVIEW_FONT = "preview_font_sp"
        const val KEY_AUTO_SAVE = "auto_save"
        const val KEY_FOLDER_SHOW_HIDDEN = "folder_show_hidden"
        const val KEY_SHOW_NO_ACCESS_NOTICE = "show_no_access_notice"
    }
}

fun themedContext(base: Context, dark: Boolean?): Context {
    if (dark == null) return base
    val night = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    val config = Configuration(base.resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
    }
    return base.createConfigurationContext(config)
}
