package com.r57zone.podcasteasy

import java.io.File

/**
 * Закладки лежат рядом с файлом: «название файла.txt». Строки показываются как есть;
 * если строка начинается со времени (1:45:14 или 01:44), по ней можно перейти. Остальные строки просто текст.
 */
object Bookmarks {
    private val TIME = Regex("""^\s*(?:(\d{1,2}):)?(\d{1,3}):(\d{2})(?!\d)""")

    fun file(mediaPath: String): File {
        val f = File(mediaPath)
        return File(f.parentFile, f.nameWithoutExtension + ".txt")
    }

    /** Время в начале строки в мс; null — если строка начинается не со времени */
    fun timeOf(line: String): Int? {
        val m = TIME.find(line) ?: return null
        val hasH = m.groupValues[1].isNotEmpty()
        val h = if (hasH) m.groupValues[1].toInt() else 0
        val min = m.groupValues[2].toInt()
        val sec = m.groupValues[3].toInt()
        if (sec > 59 || (hasH && min > 59)) return null
        val total = (h * 3600L + min * 60L + sec) * 1000L
        return if (total > Int.MAX_VALUE) null else total.toInt()
    }

    /** 6314000 → "01:45:14", 104000 → "01:44" */
    fun time(ms: Int): String {
        val s = ms.coerceAtLeast(0) / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format("%02d:%02d:%02d", h, m, sec) else String.format("%02d:%02d", m, sec)
    }

    /** Новая закладка: «01:44 - название», а без названия — только время */
    fun newLine(ms: Int, name: String) = if (name.isEmpty()) time(ms) else time(ms) + " - " + name

    /** Куда вставить новую закладку: перед первой строкой с большим временем, иначе в конец */
    fun insertIndex(lines: List<String>, ms: Int): Int {
        val i = lines.indexOfFirst { l -> timeOf(l)?.let { it > ms } == true }
        return if (i >= 0) i else lines.size
    }

    /** Все строки файла как есть; пустые строки снизу обрезаются */
    fun load(mediaPath: String): MutableList<String> {
        val out = ArrayList<String>()
        try {
            val f = file(mediaPath)
            if (f.isFile) out.addAll(f.readLines(Charsets.UTF_8))
        } catch (_: Exception) {}
        if (out.isNotEmpty()) out[0] = out[0].trimStart('﻿')
        while (out.isNotEmpty() && out.last().isBlank()) out.removeAt(out.size - 1)
        return out
    }

    fun save(mediaPath: String, lines: List<String>): Boolean = try {
        val f = file(mediaPath)
        val l = lines.toMutableList()
        while (l.isNotEmpty() && l.last().isBlank()) l.removeAt(l.size - 1)
        if (l.isEmpty()) { if (f.exists()) f.delete(); true }
        else { f.writeText(l.joinToString("\n") + "\n", Charsets.UTF_8); true }
    } catch (_: Exception) { false }

    fun exists(mediaPath: String) = file(mediaPath).isFile

    fun delete(mediaPath: String) { try { file(mediaPath).delete() } catch (_: Exception) {} }
}
