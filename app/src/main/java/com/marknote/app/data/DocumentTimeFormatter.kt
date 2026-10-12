package com.marknote.app.data

import android.content.Context
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class DocumentTimeFormatter(private val context: Context) {

    private var formatter: SimpleDateFormat? = null
    private var formatterLocale: Locale? = null
    private val lock = Any()

    fun format(epochMillis: Long): String {
        if (epochMillis <= 0) return ""
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        synchronized(lock) {
            if (formatter == null || formatterLocale != locale) {
                formatter = SimpleDateFormat(
                    DateFormat.getBestDateTimePattern(locale, TIME_SKELETON),
                    locale,
                )
                formatterLocale = locale
            }
            return formatter!!.format(Date(epochMillis))
        }
    }

    private companion object {
        const val TIME_SKELETON = "yMdHm"
    }
}
