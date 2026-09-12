package com.marknote.app.data

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * 应用内可选的界面语言。
 *
 * [tag] 为空表示「跟随系统」，其余是 BCP 47 语言标签，交给 [Locale.forLanguageTag] 解析。
 * 中文刻意按 **script** 标注（zh-Hans / zh-Hant）而不是区域（zh-CN / zh-TW）：
 * 资源目录同样是 values-b+zh+Hans / values-b+zh+Hant，一一对应，且繁体一次覆盖
 * 中国台湾、中国香港、中国澳门三地。
 *
 * [endonym] 是该语言的**自称**，刻意不走资源文件：语言列表用自称展示，
 * 这样无论当前界面是什么语言，用户都能一眼认出自己的语言。
 *
 * 注意：新增语言时除了在这里加一项，还要在 res/ 下新建对应的 values-xx/strings.xml、
 * 在 res/xml/locales_config.xml 里加一条，并把 values/strings.xml 的 80 条文案翻译过去。
 */
enum class AppLanguage(val tag: String, val endonym: String) {
    SYSTEM("", ""),
    CHINESE_SIMPLIFIED("zh-Hans", "简体中文"),
    CHINESE_TRADITIONAL("zh-Hant", "繁體中文"),
    ENGLISH("en", "English"),
    JAPANESE("ja", "日本語"),
    FRENCH("fr", "Français"),
    GERMAN("de", "Deutsch"),
    SPANISH("es", "Español"),
    ITALIAN("it", "Italiano"),
    LATIN("la", "Latina"),
    ;

    /** 交给系统「按应用语言」与资源解析用的 Locale；[SYSTEM] 时是 [Locale.ROOT]（空 locale） */
    val locale: Locale get() = if (tag.isEmpty()) Locale.ROOT else Locale.forLanguageTag(tag)

    companion object {
        /**
         * 从语言标签还原；空串、未知标签、以及归一后仍无对应的，一律回落到「跟随系统」。
         *
         * 这里做归一而不是直接比对字符串，是因为标签有多个来源：本应用旧版本持久化的
         * zh-CN / zh-TW，以及 API 33+ 系统回传的 zh-Hans-CN、en-US 这类带区域的形式。
         */
        fun fromTag(tag: String?): AppLanguage {
            val normalized = normalize(tag) ?: return SYSTEM
            return entries.firstOrNull { it.tag == normalized } ?: SYSTEM
        }

        /** zh-CN → zh-Hans、zh-TW / zh-HK → zh-Hant、en-US → en */
        private fun normalize(tag: String?): String? {
            if (tag.isNullOrBlank()) return null
            val locale = Locale.forLanguageTag(tag.replace('_', '-'))
            val language = locale.language.lowercase(Locale.ROOT)
            if (language.isEmpty() || language == "und") return null
            if (language != "zh") return language
            return if (chineseScript(locale) == "Hant") CHINESE_TRADITIONAL.tag else CHINESE_SIMPLIFIED.tag
        }

        /** 优先用显式 script；没有就按地区推断（老版本持久化的是 zh-CN / zh-TW 这种标签） */
        private fun chineseScript(locale: Locale): String =
            locale.script.ifEmpty {
                when (locale.country.uppercase(Locale.ROOT)) {
                    "TW", "HK", "MO" -> "Hant"
                    else -> "Hans"
                }
            }
    }
}

/**
 * 语言偏好的读写。
 *
 * 为什么独立于 SettingsRepository：进程最早的 attachBaseContext 阶段就要知道选了什么语言，
 * 那时还读不到 Compose 状态，只能直接查 SharedPreferences。
 * 两者共用同一个 SharedPreferences 文件与同一个键，避免出现两份真相。
 *
 * Android 13+（API 33）多了一个权威来源：系统「设置 → 应用 → 语言」。
 * 应用声明了 android:localeConfig，用户可以在那里直接改。所以：
 * - 系统里设过（applicationLocales 非空）以系统为准，并回写本地偏好，让应用内的选择器跟着同步；
 * - 系统里没设过（为空 = 跟随系统）则用本地偏好，这样即便某个机型上系统接口不可用，
 *   应用内的切换依然可靠。
 */
object AppLocaleStore {
    /** 与 SettingsRepository 共用的文件与键 */
    const val PREFS = "settings"
    const val KEY = "app_language"

    /**
     * 进程内缓存。
     *
     * [current] 会被 MarkNoteApplication.getResources() 高频调用，而查询系统「按应用语言」
     * 是一次跨进程调用，不能每次现问。缓存只在 [refresh] 与 [write] 时更新，
     * 而系统改语言后必定重建 Activity，[refresh] 的调用点正好覆盖那种情况。
     */
    @Volatile
    private var cached: AppLanguage? = null

    /** 取当前语言（走缓存）。缓存未建立时先 [refresh] 一次。 */
    fun current(context: Context): AppLanguage = cached ?: refresh(context)

    /** 重新解析一次当前语言并刷新缓存，返回结果 */
    fun refresh(context: Context): AppLanguage {
        val resolved = resolve(context)
        cached = resolved
        return resolved
    }

    /** 写入语言偏好：更新缓存 + 落盘 + 同步系统（API 33+），三处保持一致 */
    fun write(context: Context, language: AppLanguage) {
        cached = language
        prefs(context).edit().putString(KEY, language.tag).apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = if (language == AppLanguage.SYSTEM) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList(language.locale)
            }
            runCatching {
                context.getSystemService(LocaleManager::class.java)?.setApplicationLocales(locales)
            }
        }
    }

    private fun resolve(context: Context): AppLanguage {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = runCatching {
                context.getSystemService(LocaleManager::class.java)?.applicationLocales
            }.getOrNull()
            if (locales != null) {
                val language = if (locales.isEmpty) {
                    AppLanguage.SYSTEM
                } else {
                    AppLanguage.fromTag(locales[0].toLanguageTag())
                }
                // 与本地偏好对齐：在系统设置里改过之后，应用内选择器要显示同一种语言
                if (prefs(context).getString(KEY, null)?.let { AppLanguage.fromTag(it) } != language) {
                    prefs(context).edit().putString(KEY, language.tag).apply()
                }
                return language
            }
        }
        return AppLanguage.fromTag(prefs(context).getString(KEY, null))
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * 把 [base] 包一层目标语言的 Context；「跟随系统」时原样返回。
 *
 * 本项目没有引入 AppCompat（主题继承的是 android:Theme.Material.NoActionBar，
 * 不是 Theme.AppCompat，用不了 AppCompatDelegate.setApplicationLocales），
 * 所以语言切换由自己实现，需要在两个位置各自套用：
 *
 * - **Activity**（本函数，见 MainActivity.attachBaseContext）：Activity 的 base context
 *   由系统按「应用资源」创建，不受 Application 包装影响；不包的话 Compose 里
 *   stringResource 读到的仍是系统语言。
 * - **Application**（见 MarkNoteApplication.getResources）：让 applicationContext 也拿到
 *   目标语言，Repository 层用 context.getString(...) 取到的文案才会被翻译。
 *
 * 切换语言后需要重建 Activity 才会整体生效（与系统「按应用设置语言」的行为一致）。
 */
fun localizedContext(base: Context, language: AppLanguage): Context {
    if (language == AppLanguage.SYSTEM) return base
    val locale = language.locale
    val config = Configuration(base.resources.configuration).apply {
        setLocale(locale)
        setLayoutDirection(locale)
    }
    return base.createConfigurationContext(config)
}
