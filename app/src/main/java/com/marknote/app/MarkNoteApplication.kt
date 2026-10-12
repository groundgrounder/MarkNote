package com.marknote.app

import android.app.Application
import android.content.res.Configuration
import android.content.res.Resources
import com.marknote.app.data.AppLocaleStore
import java.util.Locale

class MarkNoteApplication : Application() {

    private var localizedResources: Resources? = null
    private var localizedTag: String? = null

    override fun onCreate() {
        super.onCreate()

        AppLocaleStore.refresh(this)
    }

    override fun getResources(): Resources {
        val tag = AppLocaleStore.current(this).tag

        if (tag.isEmpty()) return super.getResources()
        if (localizedResources == null || localizedTag != tag) {
            val locale = Locale.forLanguageTag(tag)

            val config = Configuration(super.getResources().configuration).apply {
                setLocale(locale)
                setLayoutDirection(locale)
            }
            localizedResources = createConfigurationContext(config).resources
            localizedTag = tag
        }
        return localizedResources!!
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        localizedResources = null
        localizedTag = null
    }
}
