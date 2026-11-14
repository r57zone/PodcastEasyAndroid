package com.r57zone.podcasteasy

import android.animation.ValueAnimator
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * Строки файла закладок. Тап — перейти (если строка начинается со времени), двойной тап — править строку целиком,
 * свайп справа налево — красный квадрат с крестиком, нажатие на него удаляет строку (как в списке файлов).
 */
class BookmarkAdapter(
    private val onOpen: (String) -> Unit,
    private val onEdit: (Int, String) -> Unit,
    private val onRemove: (Int) -> Unit
) : RecyclerView.Adapter<BookmarkAdapter.VH>() {

    private var items: List<String> = emptyList()
    private var openHolder: VH? = null

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val fg: View = v.findViewById(R.id.fg)
        val text: TextView = v.findViewById(R.id.tvText)
        val del: View = v.findViewById(R.id.btnDel)
        val padEnd = text.paddingEnd
        var open = false
        var progress = 0
        var anim: ValueAnimator? = null
    }

    fun submit(list: List<String>) { items = list.toList(); openHolder = null; notifyDataSetChanged() }

    fun attach(rv: RecyclerView) {
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(r: RecyclerView, state: Int) {
                if (state == RecyclerView.SCROLL_STATE_DRAGGING) openHolder?.let { settle(it, false) }
            }
        })
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val h = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_bookmark, parent, false))
        attachTouch(h)
        return h
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        h.anim?.cancel()
        h.open = false
        setProgress(h, 0)
        if (openHolder === h) openHolder = null
        h.text.text = items[position].trim()
        h.del.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onRemove(p)
        }
    }

    private fun maxProgress(h: VH) = h.itemView.height

    private fun setProgress(h: VH, px: Int) {
        h.progress = px
        val lp = h.del.layoutParams
        if (lp.width != px) { lp.width = px; h.del.layoutParams = lp }
        h.text.setPadding(h.text.paddingLeft, h.text.paddingTop, h.padEnd + px, h.text.paddingBottom)
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

    private fun attachTouch(h: VH) {
        val ctx = h.itemView.context
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        val taps = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val p = h.bindingAdapterPosition
                if (h.open) settle(h, false)
                else if (p != RecyclerView.NO_POSITION) onOpen(items[p])
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val p = h.bindingAdapterPosition
                if (!h.open && p != RecyclerView.NO_POSITION) onEdit(p, items[p])
                return true
            }
        })
        var downX = 0f
        var downY = 0f
        var startProgress = 0
        var decided = false
        var drag = false

        h.fg.setOnTouchListener { v, e ->
            taps.onTouchEvent(e)
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
                            drag = abs(dx) > abs(dy) && (dx < 0 || h.open)
                            if (drag) {
                                v.parent.requestDisallowInterceptTouchEvent(true)
                                val c = MotionEvent.obtain(e)
                                c.action = MotionEvent.ACTION_CANCEL
                                taps.onTouchEvent(c)      // жест — это уже не тап
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
