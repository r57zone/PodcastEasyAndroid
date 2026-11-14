package com.r57zone.podcasteasy

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Environment
import java.io.File

/**
 * Настройки — SharedPreferences ("pe").
 * Прогресс прослушивания — SQLite (progress.db): путь к файлу + пройденное время (мс) + флаг «прослушано».
 */
object Store {
    enum class Status { NEW, PARTIAL, DONE }

    private lateinit var sp: SharedPreferences
    private lateinit var db: Db

    private class Db(ctx: Context) : SQLiteOpenHelper(ctx, "progress.db", null, 2) {
        override fun onCreate(d: SQLiteDatabase) {
            d.execSQL("CREATE TABLE progress (path TEXT PRIMARY KEY, pos INTEGER NOT NULL DEFAULT 0, done INTEGER NOT NULL DEFAULT 0)")
            createMeta(d)
        }
        override fun onUpgrade(d: SQLiteDatabase, o: Int, n: Int) { createMeta(d) }
        // длительность файла + «подпись» (размер и время изменения), чтобы не считать заново
        private fun createMeta(d: SQLiteDatabase) {
            d.execSQL("CREATE TABLE IF NOT EXISTS meta (path TEXT PRIMARY KEY, dur INTEGER NOT NULL, size INTEGER NOT NULL, mtime INTEGER NOT NULL)")
        }
    }

    fun init(ctx: Context) {
        if (!::sp.isInitialized) {
            val app = ctx.applicationContext
            sp = app.getSharedPreferences("pe", Context.MODE_PRIVATE)
            db = Db(app)
        }
    }

    // ---------- настройки ----------

    fun defaultRoot(): String =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS).absolutePath

    var rootFolder: String
        get() = sp.getString("root", null) ?: defaultRoot()
        set(v) { sp.edit().putString("root", v).apply() }

    /** Начинать воспроизведение сразу после открытия файла */
    var autoplay: Boolean
        get() = sp.getBoolean("autoplay", true)
        set(v) { sp.edit().putBoolean("autoplay", v).apply() }

    /** После окончания включать следующий файл из той же папки */
    var playNext: Boolean
        get() = sp.getBoolean("play_next", false)
        set(v) { sp.edit().putBoolean("play_next", v).apply() }

    /** Видео показывать вместо обложки сразу при открытии (по умолчанию выключено) */
    var autoVideo: Boolean
        get() = sp.getBoolean("auto_video", false)
        set(v) { sp.edit().putBoolean("auto_video", v).apply() }

    /** Показывать обложку файла (у MP3 и у видео; если выключено — стандартная заглушка не показывается вовсе) */
    var coverFile: Boolean
        get() = sp.getBoolean("cover_file", true)
        set(v) { sp.edit().putBoolean("cover_file", v).apply() }

    /** Закрывать список закладок после нажатия на закладку */
    var hideBookmarksOnTap: Boolean
        get() = sp.getBoolean("hide_bm_on_tap", true)
        set(v) { sp.edit().putBoolean("hide_bm_on_tap", v).apply() }

    /** Удалять закладки (txt) вместе с файлом без вопроса */
    var alwaysDeleteBookmarks: Boolean
        get() = sp.getBoolean("always_del_bm", true)
        set(v) { sp.edit().putBoolean("always_del_bm", v).apply() }


    /** Режим показа длинного названия в плеере: 0 — "Кни…ена 4", 1 — "Книга 12…", 2 — "…Сцена 4" */
    var titleMode: Int
        get() = sp.getInt("title_mode", 0).coerceIn(0, 2)
        set(v) { sp.edit().putInt("title_mode", v).apply() }

    /** Показывать формат и битрейт/разрешение в нижней строке плеера (переключается тапом по строке) */
    var showInfo: Boolean
        get() = sp.getBoolean("show_info", false)
        set(v) { sp.edit().putBoolean("show_info", v).apply() }

    /** В плеере справа показывать оставшееся время (-2:42), а не общее */
    var showRemaining: Boolean
        get() = sp.getBoolean("remaining", false)
        set(v) { sp.edit().putBoolean("remaining", v).apply() }

    /** Уровни громкости плеера (%): 1…15 по одному, дальше крупнее */
    val VOLUME_LEVELS = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 20, 25, 30, 40, 50, 60, 70, 80, 90, 100)

    /** Громкость плеера, % (1…100). Только в памяти процесса, на диск не пишется: после выгрузки приложения — 100 % */
    var volumePercent: Int = 100
        set(v) { field = v.coerceIn(1, 100) }

    /** Скорость воспроизведения. Только в памяти процесса: после выгрузки приложения — 1x */
    var speed: Float = 1f

    /** Шаг кнопкой «громче»: ближайший уровень из VOLUME_LEVELS выше текущего */
    fun volumeUp(cur: Int): Int = VOLUME_LEVELS.firstOrNull { it > cur } ?: 100

    /** Шаг кнопкой «тише»: ближайший уровень ниже текущего */
    fun volumeDown(cur: Int): Int = VOLUME_LEVELS.lastOrNull { it < cur } ?: 1

    // ---------- длительности файлов (таблица meta) ----------

    class Meta(val dur: Int, val size: Long, val mtime: Long)

    fun allMeta(): HashMap<String, Meta> {
        val m = HashMap<String, Meta>()
        db.readableDatabase.rawQuery("SELECT path,dur,size,mtime FROM meta", null).use {
            while (it.moveToNext()) m[it.getString(0)] = Meta(it.getInt(1), it.getLong(2), it.getLong(3))
        }
        return m
    }

    /** Длительность из кэша, если файл не менялся (размер и mtime совпадают) */
    fun cachedDuration(meta: Map<String, Meta>, f: File): Int? {
        val m = meta[f.absolutePath] ?: return null
        return if (m.size == f.length() && m.mtime == f.lastModified()) m.dur else null
    }

    fun putDuration(f: File, dur: Int) {
        db.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO meta(path,dur,size,mtime) VALUES(?,?,?,?)",
            arrayOf<Any>(f.absolutePath, dur, f.length(), f.lastModified())
        )
    }

    /** Убрать из meta записи файлов этой папки, которых уже нет на диске */
    fun pruneMeta(dir: File, meta: Map<String, Meta>) {
        val base = dir.absolutePath
        val d = db.writableDatabase
        for (p in meta.keys) {
            val f = File(p)
            if (f.parent == base && !f.exists()) d.execSQL("DELETE FROM meta WHERE path=?", arrayOf<Any>(p))
        }
    }

    /** Длительность в мс (0 — не удалось определить) */
    fun readDuration(f: File): Int {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
        } catch (_: Exception) { 0 } finally { try { r.release() } catch (_: Exception) {} }
    }

    // ---------- прогресс (SQLite) ----------

    fun savePosition(path: String, pos: Int) {
        db.writableDatabase.execSQL("INSERT OR REPLACE INTO progress(path,pos,done) VALUES(?,?,0)", arrayOf<Any>(path, pos))
    }

    fun markDone(path: String) {
        db.writableDatabase.execSQL("INSERT OR REPLACE INTO progress(path,pos,done) VALUES(?,0,1)", arrayOf<Any>(path))
    }

    /** Позиция для продолжения (0, если файл прослушан или новый) */
    fun position(path: String): Int {
        db.readableDatabase.rawQuery("SELECT pos,done FROM progress WHERE path=?", arrayOf(path)).use {
            return if (it.moveToFirst() && it.getInt(1) == 0) it.getInt(0) else 0
        }
    }

    /** Удалить запись из базы (при удалении файла) */
    fun forget(path: String) {
        val d = db.writableDatabase
        d.execSQL("DELETE FROM progress WHERE path=?", arrayOf<Any>(path))
        d.execSQL("DELETE FROM meta WHERE path=?", arrayOf<Any>(path))
    }

    /** % и _ в пути — не подстановочные знаки LIKE, а обычные символы (экранируем через \) */
    private fun likeEscape(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** Файл переименовали: перенести его прогресс и длительность на новый путь */
    fun rename(old: String, new: String) {
        val d = db.writableDatabase
        d.execSQL("UPDATE OR REPLACE progress SET path=? WHERE path=?", arrayOf<Any>(new, old))
        d.execSQL("UPDATE OR REPLACE meta SET path=? WHERE path=?", arrayOf<Any>(new, old))
    }

    /** Папку переименовали: перенести записи всех файлов внутри на новый путь */
    fun renameUnder(oldDir: String, newDir: String) {
        val o = oldDir.trimEnd('/')
        val n = newDir.trimEnd('/')
        val like = likeEscape(o) + "/%"
        val d = db.writableDatabase
        // substr(path, len+1) — «хвост» пути после старой папки; сравнение по substr учитывает регистр (LIKE его не учитывает)
        for (t in arrayOf("progress", "meta")) {
            d.execSQL(
                "UPDATE OR REPLACE $t SET path = ? || substr(path, ?) WHERE path LIKE ? ESCAPE '\\' AND substr(path, 1, ?) = ?",
                arrayOf<Any>(n, o.length + 1, like, o.length, o)
            )
        }
    }

    /** Папку удалили целиком: убрать записи всех файлов внутри */
    fun forgetUnder(dir: String) {
        val d = db.writableDatabase
        val like = likeEscape(dir.trimEnd('/')) + "/%"
        d.execSQL("DELETE FROM progress WHERE path LIKE ? ESCAPE '\\'", arrayOf<Any>(like))
        d.execSQL("DELETE FROM meta WHERE path LIKE ? ESCAPE '\\'", arrayOf<Any>(like))
    }

    private fun statusOf(pos: Int, done: Int) = when {
        done != 0 -> Status.DONE
        pos < 1000 -> Status.NEW
        else -> Status.PARTIAL
    }

    /** Статусы всех файлов одним запросом (для списка) */
    fun allStatuses(): HashMap<String, Status> {
        val m = HashMap<String, Status>()
        db.readableDatabase.rawQuery("SELECT path,pos,done FROM progress", null).use {
            while (it.moveToNext()) m[it.getString(0)] = statusOf(it.getInt(1), it.getInt(2))
        }
        return m
    }

    /** Сколько прослушано у каждого файла (мс); у прослушанных до конца записи нет */
    fun allPositions(): HashMap<String, Int> {
        val m = HashMap<String, Int>()
        db.readableDatabase.rawQuery("SELECT path,pos FROM progress WHERE done=0", null).use {
            while (it.moveToNext()) m[it.getString(0)] = it.getInt(1)
        }
        return m
    }

    class Row(val path: String, val pos: Int, val done: Int)

    fun allRows(): List<Row> {
        val l = ArrayList<Row>()
        db.readableDatabase.rawQuery("SELECT path,pos,done FROM progress ORDER BY path", null).use {
            while (it.moveToNext()) l.add(Row(it.getString(0), it.getInt(1), it.getInt(2)))
        }
        return l
    }

    fun importRows(rows: List<Row>) {
        val d = db.writableDatabase
        d.beginTransaction()
        try {
            for (r in rows) d.execSQL("INSERT OR REPLACE INTO progress(path,pos,done) VALUES(?,?,?)", arrayOf<Any>(r.path, r.pos, r.done))
            d.setTransactionSuccessful()
        } finally { d.endTransaction() }
    }

    // ---------- файлы ----------

    val exts = setOf("mp3", "mp4", "ogg", "oga", "opus", "m4a", "aac", "wav", "flac", "m4b", "3gp", "mkv", "webm")

    fun isMedia(f: File) = f.isFile && f.extension.lowercase() in exts
    fun isVideoExt(f: File) = f.extension.lowercase() in setOf("mp4", "mkv", "webm", "3gp")

    /** Папки сверху, затем файлы; всё по имени. Читается заново при каждом вызове (для pull-to-refresh). */
    fun list(dir: File): List<File> {
        val all = dir.listFiles() ?: return emptyList()
        val dirs = all.filter { it.isDirectory && !it.name.startsWith(".") }.sortedBy { it.name.lowercase() }
        val files = all.filter { isMedia(it) }.sortedBy { it.name.lowercase() }
        return dirs + files
    }

    /** Следующий медиафайл в той же папке (в порядке списка) или null */
    fun nextInFolder(path: String): String? {
        val f = File(path)
        val files = f.parentFile?.let { list(it).filter { x -> x.isFile } } ?: return null
        val i = files.indexOfFirst { it.absolutePath == f.absolutePath }
        return if (i >= 0 && i + 1 < files.size) files[i + 1].absolutePath else null
    }
}
