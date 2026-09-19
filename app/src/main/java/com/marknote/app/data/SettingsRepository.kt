package com.marknote.app.data

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * 主题偏好的**早期读取**。
 *
 * 为什么独立于 [SettingsRepository]：与语言同理（见 [AppLocaleStore]），深浅色是在
 * `attachBaseContext` 阶段就套到 Context 上的（见 [themedContext]），那时还读不到 Compose 状态，
 * 只能直接查 SharedPreferences。两者共用同一个 prefs 文件与同一个键，避免出现两份真相。
 */
object AppThemeStore {
    /** 与 SettingsRepository 共用的键 */
    const val KEY = "theme_mode"

    /**
     * 当前是否被**强制**指定了深浅色：DARK → true，LIGHT → false，
     * 「跟随系统」或无值 → null（表示不要去覆盖系统给的 night 配置）。
     */
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
            ThemeMode.valueOf(prefs.getString(AppThemeStore.KEY, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
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

    /**
     * 文件夹浏览是否显示隐藏文件（名字以 `.` 开头）。
     *
     * 默认 **false = 不显示**：笔记目录里 `.git`、`.obsidian`、`.DS_Store` 这类条目几乎全是噪声，
     * 而它们是「看得见就一定会看到」的东西。需要翻 `.gitignore` 这类文件的人再打开这个开关。
     */
    var folderShowHiddenFiles by mutableStateOf(prefs.getBoolean(KEY_FOLDER_SHOW_HIDDEN, false))
        private set

    fun updateThemeMode(mode: ThemeMode) {
        themeMode = mode
        prefs.edit().putString(AppThemeStore.KEY, mode.name).apply()
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

    fun updateFolderShowHiddenFiles(show: Boolean) {
        folderShowHiddenFiles = show
        prefs.edit().putBoolean(KEY_FOLDER_SHOW_HIDDEN, show).apply()
    }

    private companion object {
        const val KEY_EDITOR_FONT = "editor_font_sp"
        const val KEY_PREVIEW_FONT = "preview_font_sp"
        const val KEY_AUTO_SAVE = "auto_save"
        const val KEY_FOLDER_SHOW_HIDDEN = "folder_show_hidden"
    }
}

/**
 * 把 [base] 包一层「应用内选定的深浅色」，[dark] 为 null（跟随系统）时原样返回。
 *
 * **为什么需要它**：系统栏图标的深浅与窗口背景并不看 Compose 的配色，而是看两条**资源层**的线索：
 *
 * - `enableEdgeToEdge()` 判断该用深色还是浅色图标，读的是 `resources.configuration.uiMode`；
 * - `values-night/themes.xml`（冷启动窗口背景，避免白闪）由 **night 限定符**决定用不用。
 *
 * 这两处的默认口径都只反映**系统**设置。于是「系统浅色 + 应用内选深色」时，深色图标会叠在深色
 * 背景上几乎看不见 —— 实测切到深色后 `dumpsys window` 里 `mAppearance` 一格没动。把 uiMode
 * 跟着应用主题一起覆盖，这两处就都自然跟随了。
 *
 * ⚠️ 与语言（[localizedContext]）同理：这个包装**只在 attachBaseContext 阶段做一次**，
 * 运行中改不了已经用出去的 Resources，所以切换主题必须重建 Activity（见 SettingsScreen）。
 *
 * 注意只覆盖 `UI_MODE_NIGHT_MASK` 那两位，car / desk / television 等其它 uiMode 位保持原样。
 * applicationContext 不做这个包装 —— 仓库层只用它取字符串，没有 night 限定的资源。
 */
fun themedContext(base: Context, dark: Boolean?): Context {
    if (dark == null) return base
    val night = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    val config = Configuration(base.resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
    }
    return base.createConfigurationContext(config)
}
