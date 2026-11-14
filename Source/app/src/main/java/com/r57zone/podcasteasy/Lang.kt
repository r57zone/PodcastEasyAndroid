package com.r57zone.podcasteasy

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** Язык интерфейса: "auto" (как в системе) или код из ALL. Хранится в prefs "pe". */
object Lang {
    /** Поддерживаемые языки: код → название на самом языке (в меню выбора не переводится) */
    val ALL = listOf(
        "en" to "English", "ru" to "Русский", "zh-CN" to "中文（简体）", "zh-TW" to "中文（繁體）",
        "es" to "Español", "pt" to "Português (Brasil)", "fr" to "Français", "de" to "Deutsch",
        "ja" to "日本語", "ar" to "العربية", "it" to "Italiano", "ko" to "한국어", "tr" to "Türkçe"
    )

    @Volatile var version = 0   // растёт при смене языка — открытые экраны пересоздаются

    fun pref(ctx: Context): String = ctx.getSharedPreferences("pe", Context.MODE_PRIVATE).getString("lang", "auto") ?: "auto"

    fun set(ctx: Context, v: String) {
        ctx.getSharedPreferences("pe", Context.MODE_PRIVATE).edit().putString("lang", v).apply()
        version++
    }

    private fun locale(ctx: Context): Locale? {
        val p = pref(ctx)
        return if (ALL.any { it.first == p }) Locale.forLanguageTag(p) else null
    }

    fun wrap(base: Context): Context {
        val l = locale(base) ?: return base
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(l)
        return base.createConfigurationContext(cfg)
    }
}

/** Тема: "auto" (как в системе), "light", "dark" */
object Appearance {
    fun pref(ctx: Context): String = ctx.getSharedPreferences("pe", Context.MODE_PRIVATE).getString("theme", "auto") ?: "auto"

    private fun mode(v: String) = when (v) {
        "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        "dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    fun apply(ctx: Context) = androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode(pref(ctx)))

    fun set(ctx: Context, v: String) {
        ctx.getSharedPreferences("pe", Context.MODE_PRIVATE).edit().putString("theme", v).apply()
        apply(ctx)   // открытые экраны пересоздаются сами
    }
}
