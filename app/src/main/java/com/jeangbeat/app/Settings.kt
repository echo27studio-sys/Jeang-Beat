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

    /** Browser: play a file as soon as it is tapped. */
    var autoPreview: Boolean
        get() = prefs.getBoolean(KEY_AUTO_PREVIEW, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_PREVIEW, value).apply()

    var metronome: Boolean
        get() = prefs.getBoolean(KEY_METRONOME, false)
        set(value) = prefs.edit().putBoolean(KEY_METRONOME, value).apply()

    /** Bars of count-in before recording (0 = none). */
    var countInBars: Int
        get() = prefs.getInt(KEY_COUNT_IN, 1)
        set(value) = prefs.edit().putInt(KEY_COUNT_IN, value).apply()

    /** Set once the notification permission was asked for (Android 13+). */
    var askedNotifications: Boolean
        get() = prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false)
        set(value) = prefs.edit().putBoolean(KEY_ASKED_NOTIFICATIONS, value).apply()

    companion object {
        private const val KEY_METRONOME = "metronome"
        private const val KEY_COUNT_IN = "count_in_bars"
        private const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"
        private const val KEY_AUTO_PREVIEW = "browser_auto_preview"
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
