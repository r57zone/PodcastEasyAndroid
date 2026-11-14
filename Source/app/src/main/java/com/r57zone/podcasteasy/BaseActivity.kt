package com.r57zone.podcasteasy

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatActivity

/** Общая база: применяет выбранный в настройках язык и пересоздаёт экран при его смене. */
open class BaseActivity : AppCompatActivity() {
    private var langVer = Lang.version

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        Appearance.apply(this)
        super.onCreate(savedInstanceState)
        // Интерфейс всегда слева направо (кнопки плеера, свайп строк, полоса прогресса): арабский текст всё равно выводится справа налево
        window.decorView.layoutDirection = android.view.View.LAYOUT_DIRECTION_LTR
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Lang.wrap(newBase))
    }

    // AppCompat 1.2+ иначе сбрасывает локаль, заданную через createConfigurationContext
    override fun applyOverrideConfiguration(overrideConfiguration: Configuration?) {
        if (overrideConfiguration != null) {
            val uiMode = overrideConfiguration.uiMode
            overrideConfiguration.setTo(baseContext.resources.configuration)
            overrideConfiguration.uiMode = uiMode
        }
        super.applyOverrideConfiguration(overrideConfiguration)
    }

    override fun onResume() {
        super.onResume()
        if (langVer != Lang.version) {
            langVer = Lang.version
            recreate()
        }
    }
}
