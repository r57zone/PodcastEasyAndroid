package com.r57zone.podcasteasy

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.OutputStream

/**
 * Экспорт/импорт прогресса: <podcasteasy version="1"><item path="..." pos="мс" done="0|1"/>…</podcasteasy>
 * Путь пишется относительно корневой папки (Книга/глава 1.mp3); файл вне корневой папки — абсолютным путём.
 */
object ProgressXml {
    fun write(out: OutputStream, rows: List<Store.Row>, root: String) {
        val base = root.trimEnd('/') + "/"
        val s = Xml.newSerializer()
        s.setOutput(out, "UTF-8")
        s.startDocument("UTF-8", true)
        s.startTag(null, "podcasteasy")
        s.attribute(null, "version", "1")
        for (r in rows) {
            s.startTag(null, "item")
            s.attribute(null, "path", if (r.path.startsWith(base)) r.path.substring(base.length) else r.path)
            s.attribute(null, "pos", r.pos.toString())
            s.attribute(null, "done", r.done.toString())
            s.endTag(null, "item")
        }
        s.endTag(null, "podcasteasy")
        s.endDocument()
        out.flush()
    }

    fun read(inp: InputStream, root: String): List<Store.Row> {
        val base = root.trimEnd('/') + "/"
        val p = Xml.newPullParser()
        p.setInput(inp, "UTF-8")
        val rows = ArrayList<Store.Row>()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.name == "item") {
                val path = p.getAttributeValue(null, "path")
                if (!path.isNullOrEmpty()) {
                    rows.add(
                        Store.Row(
                            if (path.startsWith("/")) path else base + path,
                            p.getAttributeValue(null, "pos")?.toIntOrNull() ?: 0,
                            if (p.getAttributeValue(null, "done") == "1") 1 else 0
                        )
                    )
                }
            }
            ev = p.next()
        }
        return rows
    }
}
