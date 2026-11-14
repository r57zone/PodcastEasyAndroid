package com.r57zone.podcasteasy

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import java.io.File

class SettingsActivity : BaseActivity() {

    private lateinit var tvFolder: TextView
    private lateinit var btnAccess: Button
    private lateinit var btnLang: Button
    private lateinit var btnTheme: Button
    private lateinit var btnSound: Button

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument()) { uri: Uri? ->
        if (uri != null) doExport(uri)
    }
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) doImport(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        setContentView(R.layout.activity_settings)
        tvFolder = findViewById(R.id.tvFolder)
        btnAccess = findViewById(R.id.btnAccess)
        btnLang = findViewById(R.id.btnLang)
        btnTheme = findViewById(R.id.btnTheme)
        btnSound = findViewById(R.id.btnSound)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnChoose).setOnClickListener { pickFolder(File(Store.rootFolder)) }
        findViewById<Button>(R.id.btnDefault).setOnClickListener {
            Store.rootFolder = Store.defaultRoot()
            updateUi()
        }
        btnAccess.setOnClickListener { Access.requestFiles(this) }

        btnLang.setOnClickListener { v ->
            val m = PopupMenu(this, v)
            m.menu.add(0, 0, 0, R.string.lang_auto)
            Lang.ALL.forEachIndexed { i, l -> m.menu.add(0, i + 1, i + 1, l.second) }
            m.setOnMenuItemClickListener {
                Lang.set(this, if (it.itemId == 0) "auto" else Lang.ALL[it.itemId - 1].first)
                recreate()
                true
            }
            m.show()
        }

        btnTheme.setOnClickListener { v ->
            val m = PopupMenu(this, v)
            m.menu.add(0, 0, 0, R.string.theme_auto)
            m.menu.add(0, 1, 1, R.string.theme_light)
            m.menu.add(0, 2, 2, R.string.theme_dark)
            m.setOnMenuItemClickListener {
                Appearance.set(this, arrayOf("auto", "light", "dark")[it.itemId])
                true
            }
            m.show()
        }

        // моно/стерео — системная настройка (Специальные возможности → Аудио → Моно): приложение само каналы не смешивает,
        // поэтому кнопка показывает текущее состояние и открывает настройки специальных возможностей
        btnSound.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this, R.string.mono_hint, Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
        }

        // «о приложении»: дата последнего обновления задаётся вручную в strings.xml (update_date)
        findViewById<TextView>(R.id.tvUpdate).text = getString(R.string.last_update, getString(R.string.update_date))
        buildLinks()

        findViewById<CheckBox>(R.id.cbAutoplay).apply {
            isChecked = Store.autoplay
            setOnCheckedChangeListener { _, c -> Store.autoplay = c }
        }
        findViewById<CheckBox>(R.id.cbNext).apply {
            isChecked = Store.playNext
            setOnCheckedChangeListener { _, c -> Store.playNext = c }
        }
        findViewById<CheckBox>(R.id.cbAutoVideo).apply {
            isChecked = Store.autoVideo
            setOnCheckedChangeListener { _, c -> Store.autoVideo = c }
        }
        findViewById<CheckBox>(R.id.cbCoverFile).apply {
            isChecked = Store.coverFile
            setOnCheckedChangeListener { _, c -> Store.coverFile = c }
        }
        findViewById<CheckBox>(R.id.cbHideBookmarks).apply {
            isChecked = Store.hideBookmarksOnTap
            setOnCheckedChangeListener { _, c -> Store.hideBookmarksOnTap = c }
        }
        findViewById<CheckBox>(R.id.cbDelBookmarks).apply {
            isChecked = Store.alwaysDeleteBookmarks
            setOnCheckedChangeListener { _, c -> Store.alwaysDeleteBookmarks = c }
        }

        findViewById<Button>(R.id.btnExport).setOnClickListener { exportLauncher.launch("podcasts_db_" + java.text.SimpleDateFormat("dd.MM.yy", java.util.Locale.US).format(java.util.Date()) + ".xml") }
        // в выборе показываем только xml
        findViewById<Button>(R.id.btnImport).setOnClickListener { importLauncher.launch(arrayOf("text/xml", "application/xml")) }
    }

    override fun onResume() {
        super.onResume()
        updateUi()
    }

    /** Ссылки: маленькие одинаковые кнопки в два столбца; Telegram и VK — только для русского */
    private fun buildLinks() {
        val box = findViewById<LinearLayout>(R.id.links)
        val items = ArrayList<Pair<String, String>>()
        items.add("GitHub" to "https://github.com/r57zone/PodcastEasyAndroid")
        items.add(getString(R.string.link_donate) to "https://dalink.to/r57zone")
        // ru: GitHub, Поддержать | Telegram, VK | YouTube, Другие проекты
        // остальные языки: GitHub, Donate | Telegram (en), X | YouTube, More projects
        if (resources.configuration.locales[0].language == "ru") {
            items.add("Telegram" to "https://t.me/r57zone")
            items.add("VK" to "https://vk.ru/r57zone")
        } else {
            items.add("Telegram" to "https://t.me/r57zone_en")
            items.add("X" to "https://x.com/r57zone")
        }
        items.add("YouTube" to "https://www.youtube.com/@r57zone")
        items.add(getString(R.string.link_more) to "https://github.com/r57zone/")
        val d = resources.displayMetrics.density
        val m = (3 * d).toInt()
        for (i in items.indices step 2) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            // последняя кнопка при нечётном числе стоит по центру (по бокам пустые половинки), размер у всех одинаковый
            val single = i + 1 >= items.size
            if (single) row.addView(View(this), LinearLayout.LayoutParams(0, 0, 0.5f))
            for (j in i..minOf(i + 1, items.size - 1)) {
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                lp.setMargins(m, m, m, m)
                row.addView(linkButton(items[j].first, items[j].second, d), lp)
            }
            if (single) row.addView(View(this), LinearLayout.LayoutParams(0, 0, 0.5f))
            box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun linkButton(text: String, url: String, d: Float): MaterialButton {
        val b = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        b.text = text
        b.textSize = 13f
        b.minHeight = 0
        b.minimumHeight = (36 * d).toInt()
        b.insetTop = 0
        b.insetBottom = 0
        b.setPadding((8 * d).toInt(), 0, (8 * d).toInt(), 0)
        b.maxLines = 1
        b.ellipsize = TextUtils.TruncateAt.END
        b.setOnClickListener {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
        }
        return b
    }

    private fun updateUi() {
        tvFolder.text = Store.rootFolder
        btnAccess.visibility = if (Access.hasFiles(this)) View.GONE else View.VISIBLE
        btnTheme.setText(
            when (Appearance.pref(this)) {
                "light" -> R.string.theme_light
                "dark" -> R.string.theme_dark
                else -> R.string.theme_auto
            }
        )
        val mono = try { Settings.System.getInt(contentResolver, "master_mono", 0) == 1 } catch (_: Exception) { false }
        btnSound.setText(if (mono) R.string.sound_mono else R.string.sound_stereo)
        btnLang.text = Lang.ALL.firstOrNull { it.first == Lang.pref(this) }?.second ?: getString(R.string.lang_auto)
    }

    private fun doExport(uri: Uri) {
        try {
            val rows = Store.allRows()
            contentResolver.openOutputStream(uri)?.use { ProgressXml.write(it, rows, Store.rootFolder) }
            Toast.makeText(this, getString(R.string.export_done), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Error", Toast.LENGTH_LONG).show()
        }
    }

    private fun doImport(uri: Uri) {
        try {
            val rows = contentResolver.openInputStream(uri)?.use { ProgressXml.read(it, Store.rootFolder) } ?: emptyList()
            Store.importRows(rows)
            Toast.makeText(this, getString(R.string.import_done), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.import_error, Toast.LENGTH_LONG).show()
        }
    }

    /** Простой выбор папки: список подпапок, «..» — вверх, «Выбрать» — текущая папка. */
    private fun pickFolder(start: File) {
        val dir = if (start.isDirectory) start else File(Store.defaultRoot()).let { if (it.isDirectory) it else it.parentFile ?: it }
        val subs = (dir.listFiles() ?: emptyArray()).filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedBy { it.name.lowercase() }
        val up = dir.parentFile
        val names = ArrayList<String>()
        if (up != null) names.add("..")
        names.addAll(subs.map { it.name + "/" })

        AlertDialog.Builder(this)
            .setTitle(dir.absolutePath)
            .setItems(names.toTypedArray()) { _, which ->
                val target = if (up != null) { if (which == 0) up else subs[which - 1] } else subs[which]
                pickFolder(target)
            }
            .setPositiveButton(R.string.select) { _, _ ->
                Store.rootFolder = dir.absolutePath
                updateUi()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
