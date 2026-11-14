package com.r57zone.podcasteasy

object Fmt {
    /** 75000 -> "1:15", 3725000 -> "1:02:05" */
    fun time(ms: Int): String {
        val s = ms.coerceAtLeast(0) / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec)
    }

    /** Для списка файлов: 104000 -> "01:44", 6314000 -> "01:45:14" */
    fun clock(ms: Int): String {
        val s = ms.coerceAtLeast(0) / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format("%02d:%02d:%02d", h, m, sec) else String.format("%02d:%02d", m, sec)
    }
}
