package com.r57zone.podcasteasy

import android.animation.ValueAnimator
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import kotlin.math.abs

/**
 * Список файлов и папок. У файлов — свайп справа налево: строка не двигается, справа растёт красный
 * квадрат с крестиком, а текст сужается; нажатие на квадрат удаляет файл (без подтверждения).
 * Прокрутка списка закрывает открытую строку.
 * Долгое нажатие включает выбор нескольких строк: пока что-то выбрано, нажатие переключает выбор, а свайп отключён.
 */
class FileAdapter(
    private val onClick: (File) -> Unit,
    private val onDelete: (File) -> Unit,
    private val onSelection: (Int) -> Unit
) : RecyclerView.Adapter<FileAdapter.VH>() {

    private var items: MutableList<File> = ArrayList()
    private var statuses: Map<String, Store.Status> = emptyMap()
    private var durations: HashMap<String, Int> = HashMap()
    private var positions: Map<String, Int> = emptyMap()
    private var playingPath: String? = null
    private var openHolder: VH? = null
    private val selected = LinkedHashSet<String>()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val fg: View = v.findViewById(R.id.fg)
        val content: View = v.findViewById(R.id.content)
        val del: View = v.findViewById(R.id.btnDel)
        val icon: ImageView = v.findViewById(R.id.ivIcon)
        val name: TextView = v.findViewById(R.id.tvName)
        val dur: TextView = v.findViewById(R.id.tvDur)
        val padEnd = content.paddingEnd
        var open = false
        var progress = 0           // текущая ширина красного квадрата, px
        var anim: ValueAnimator? = null
    }

    fun submit(list: List<File>, st: Map<String, Store.Status>, pos: Map<String, Int>, dur: Map<String, Int>, playing: String?) {
        items = ArrayList(list)
        statuses = st
        positions = pos
        durations = HashMap(dur)
        playingPath = playing
        openHolder = null
        // выбранные, которых в новом списке уже нет, из выбора убираем
        val before = selected.size
        selected.retainAll(items.map { it.absolutePath }.toSet())
        notifyDataSetChanged()
        if (selected.size != before) onSelection(selected.size)
    }

    // ---------- выбор нескольких ----------

    fun selectedCount() = selected.size

    /** Выбранные строки в порядке списка */
    fun selectedFiles(): List<File> = items.filter { it.absolutePath in selected }

    fun clearSelection() {
        if (selected.isEmpty()) return
        selected.clear()
        notifyDataSetChanged()
        onSelection(0)
    }

    private fun toggle(f: File) {
        val p = f.absolutePath
        if (!selected.add(p)) selected.remove(p)
        val i = items.indexOfFirst { it.absolutePath == p }
        if (i >= 0) notifyItemChanged(i, Unit)          // с payload — без «мигания» при смене строки
        onSelection(selected.size)
    }

    fun setPlaying(p: String?) {
        if (p != playingPath) { playingPath = p; notifyDataSetChanged() }
    }

    /** Длительность досчиталась в фоне — обновить одну строку */
    fun setDuration(path: String, ms: Int) {
        durations[path] = ms
        val i = items.indexOfFirst { it.absolutePath == path }
        if (i >= 0) notifyItemChanged(i)
    }

    fun fileCount() = items.count { it.isFile }

    /** Сколько материала в папке прослушано, % по времени; null — если файлов нет или у кого-то длительность ещё неизвестна */
    fun listenedPercent(): Int? {
        var total = 0L
        var played = 0L
        var any = false
        for (f in items) if (f.isFile) {
            val d = durations[f.absolutePath] ?: 0
            if (d <= 0) return null
            any = true
            total += d
            played += if (statuses[f.absolutePath] == Store.Status.DONE) d
                      else (positions[f.absolutePath] ?: 0).coerceIn(0, d)
        }
        return if (any && total > 0) (played * 100 / total).toInt() else null
    }

    fun remove(f: File) {
        val i = items.indexOfFirst { it.absolutePath == f.absolutePath }
        if (i >= 0) {
            openHolder = null
            items.removeAt(i)
            notifyItemRemoved(i)
        }
    }

    fun closeOpen() {
        openHolder?.let { settle(it, false) }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val h = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false))
        attachSwipe(h)
        return h
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val f = items[position]
        h.anim?.cancel()
        h.open = false
        setProgress(h, 0)
        if (openHolder === h) openHolder = null

        h.icon.clearColorFilter()
        if (f.isDirectory) {
            h.icon.setImageResource(R.drawable.ic_folder)
            h.icon.setColorFilter(h.itemView.context.getColor(R.color.folder))
            h.name.text = f.name
            h.dur.visibility = View.GONE
        } else {
            h.name.text = f.nameWithoutExtension
            val d = durations[f.absolutePath] ?: 0
            val done = statuses[f.absolutePath] == Store.Status.DONE
            // «прослушано / всего»; пока длительность не известна — 00:00 / 00:00 (строки не прыгают)
            val played = if (done) d else (positions[f.absolutePath] ?: 0)
            h.dur.text = Fmt.clock(played) + " / " + Fmt.clock(d)
            h.dur.visibility = View.VISIBLE
            val playing = f.absolutePath == playingPath
            val res = if (playing) R.drawable.ic_audio else when (statuses[f.absolutePath] ?: Store.Status.NEW) {
                Store.Status.NEW -> R.drawable.dot_new
                Store.Status.PARTIAL -> R.drawable.dot_half
                Store.Status.DONE -> R.drawable.dot_done
            }
            h.icon.setImageResource(res)
            if (playing) h.icon.setColorFilter(h.itemView.context.getColor(R.color.fg))   // играющий: цвет шрифта (тёмный / светлый по теме)
        }

        // выбранная строка подсвечена цветом акцента поверх фона (цвет сплошной, чтобы не просвечивало)
        val ctx = h.itemView.context
        val bg = ctx.getColor(R.color.bg)
        h.fg.setBackgroundColor(
            if (f.absolutePath in selected) ColorUtils.compositeColors(ColorUtils.setAlphaComponent(ctx.getColor(R.color.accent), 70), bg)
            else bg
        )

        h.fg.setOnClickListener {
            if (selected.isNotEmpty()) toggle(f)
            else if (h.open) settle(h, false) else onClick(f)
        }
        h.fg.setOnLongClickListener {
            openHolder?.let { o -> settle(o, false) }
            toggle(f)
            true
        }
        h.del.setOnClickListener { onDelete(f) }
    }

    /** Ширина красного квадрата = высоте строки */
    private fun maxProgress(h: VH) = h.itemView.height

    /** Красный квадрат шириной px; текст при этом сужается (отступ справа растёт), но не сдвигается */
    private fun setProgress(h: VH, px: Int) {
        h.progress = px
        val lp = h.del.layoutParams
        if (lp.width != px) { lp.width = px; h.del.layoutParams = lp }
        h.content.setPadding(h.content.paddingLeft, h.content.paddingTop, h.padEnd + px, h.content.paddingBottom)
    }

    private fun settle(h: VH, open: Boolean) {
        h.open = open
        val target = if (open) maxProgress(h) else 0
        h.anim?.cancel()
        h.anim = ValueAnimator.ofInt(h.progress, target).apply {
            duration = 150
            addUpdateListener { setProgress(h, it.animatedValue as Int) }
            start()
        }
        if (open) {
            if (openHolder !== h) openHolder?.let { settle(it, false) }
            openHolder = h
        } else if (openHolder === h) openHolder = null
    }

    private fun attachSwipe(h: VH) {
        val slop = ViewConfiguration.get(h.itemView.context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startProgress = 0
        var decided = false
        var drag = false

        h.fg.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startProgress = h.progress
                    decided = false; drag = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!decided) {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (abs(dx) > slop || abs(dy) > slop) {
                            decided = true
                            val pos = h.bindingAdapterPosition
                            val valid = pos != RecyclerView.NO_POSITION
                            drag = valid && selected.isEmpty() && abs(dx) > abs(dy) && (dx < 0 || h.open)
                            if (drag) {
                                v.parent.requestDisallowInterceptTouchEvent(true)
                                // отменить нажатие/ripple у самой строки
                                val c = MotionEvent.obtain(e)
                                c.action = MotionEvent.ACTION_CANCEL
                                v.onTouchEvent(c)
                                c.recycle()
                                h.anim?.cancel()
                                if (openHolder !== h) openHolder?.let { settle(it, false) }
                            }
                        }
                    }
                    if (drag) {
                        setProgress(h, (startProgress + (downX - e.rawX)).toInt().coerceIn(0, maxProgress(h)))
                        true
                    } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (drag) {
                        drag = false
                        settle(h, h.progress > maxProgress(h) / 2)
                        true
                    } else false
                }
                else -> false
            }
        }
    }
}
