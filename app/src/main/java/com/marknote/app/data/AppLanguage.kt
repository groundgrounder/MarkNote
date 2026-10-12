package com.marknote.app.data

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

enum class AppLanguage(val tag: String, val endonym: String) {
    SYSTEM("", ""),
    CHINESE_SIMPLIFIED("zh-Hans", "简体中文"),
    CHINESE_TRADITIONAL("zh-Hant", "繁體中文"),
    ENGLISH("en", "English"),
    LATIN("la", "Latina"),
    ;

    val locale: Locale get() = if (tag.isEmpty()) Locale.ROOT else Locale.forLanguageTag(tag)

    companion object {

        fun fromTag(tag: String?): AppLanguage {
            val normalized = normalize(tag) ?: return SYSTEM
            return entries.firstOrNull { it.tag == normalized } ?: SYSTEM
        }

        /** Normalize common locale tags to the app's supported language tags. */
        private fun normalize(tag: String?): String? {
            if (tag.isNullOrBlank()) return null
            val locale = Locale.forLanguageTag(tag.replace('_', '-'))
            val language = locale.language.lowercase(Locale.ROOT)
            if (language.isEmpty() || language == "und") return null
            if (language != "zh") return language
            return if (chineseScript(locale) == "Hant") CHINESE_TRADITIONAL.tag else CHINESE_SIMPLIFIED.tag
        }

        private fun chineseScript(locale: Locale): String =
            locale.script.ifEmpty {
                when (locale.country.uppercase(Locale.ROOT)) {
                    "TW", "HK", "MO" -> "Hant"
                    else -> "Hans"
                }
            }
    }
}

object AppLocaleStore {

    const val PREFS = "settings"
    const val KEY = "app_language"

    @Volatile
    private var cached: AppLanguage? = null

    fun current(context: Context): AppLanguage = cached ?: refresh(context)

    fun refresh(context: Context): AppLanguage {
        val resolved = resolve(context)
        cached = resolved
        return resolved
    }

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
                val reported = if (locales.isEmpty) null else locales[0].toLanguageTag()
                val language = AppLanguage.fromTag(reported)

                if (reported != null && language == AppLanguage.SYSTEM) {
                    runCatching {
                        context.getSystemService(LocaleManager::class.java)
                            ?.setApplicationLocales(LocaleList.getEmptyLocaleList())
                    }
                }

                if (prefs(context).getString(KEY, null).orEmpty() != language.tag) {
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

fun localizedContext(base: Context, language: AppLanguage): Context {
    if (language == AppLanguage.SYSTEM) return base
    val locale = language.locale
    val config = Configuration(base.resources.configuration).apply {
        setLocale(locale)
        setLayoutDirection(locale)
    }
    return base.createConfigurationContext(config)
}
