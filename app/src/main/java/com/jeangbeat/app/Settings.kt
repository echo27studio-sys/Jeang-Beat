package com.jeangbeat.app

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    ENGLISH("en"),
    KHMER("km"),
}

/** Small key-value settings stored in SharedPreferences. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var language: AppLanguage
        get() = runCatching { AppLanguage.valueOf(prefs.getString(KEY_LANGUAGE, null) ?: "") }
            .getOrDefault(AppLanguage.SYSTEM)
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value.name).apply()

    var lastProjectFile: String?
        get() = prefs.getString(KEY_LAST_PROJECT, null)
        set(value) = prefs.edit().putString(KEY_LAST_PROJECT, value).apply()

    companion object {
        private const val KEY_LANGUAGE = "language"
        private const val KEY_LAST_PROJECT = "last_project"

        /** Returns a context whose resources use the chosen app language. */
        fun wrap(base: Context): Context {
            val tag = Settings(base).language.tag ?: return base
            val locale = Locale.forLanguageTag(tag)
            Locale.setDefault(locale)
            val config = Configuration(base.resources.configuration)
            config.setLocale(locale)
            return base.createConfigurationContext(config)
        }
    }
}
