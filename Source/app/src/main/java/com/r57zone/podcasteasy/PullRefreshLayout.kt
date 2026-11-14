package com.r57zone.podcasteasy

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ProgressBar

/**
 * Pull-to-refresh: список (единственный ребёнок из разметки) сдвигается вниз вслед за пальцем,
 * а в освободившемся месте сразу под экшен-баром показывается индикатор.
 * Во время обновления список остаётся сдвинутым ровно на высоту индикатора с небольшими отступами.
 */
class PullRefreshLayout @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : FrameLayout(c, a) {

    private val d = resources.displayMetrics.density
    private val pad = 3 * d                       // микро-отступ над и под индикатором
    private val size = (48 * 0.65f * d).toInt()           // диаметр индикатора
    private val gap = pad * 2 + size              // на столько сдвигается список при обновлении
    private val slop = ViewConfiguration.get(c).scaledTouchSlop

    // индикатор добавляется первым — список из разметки рисуется поверх него и открывает его при сдвиге
    private val spinner = ProgressBar(c).apply { isIndeterminate = true; visibility = View.INVISIBLE }
    private var listener: (() -> Unit)? = null
    private var offset = 0f
    private var startX = 0f
    private var startY = 0f
    private var dragging = false
    private var anim: ValueAnimator? = null

    private var refreshing = false
    private var shownAt = 0L
    private val finish = Runnable { refreshing = false; animateTo(0f) }

    /**
     * Идёт обновление: список сдвинут. false — вернуть список на место, но не раньше чем через MIN_SHOW_MS
     * после начала, чтобы быстрое обновление не мигало: сдвинулось, круг успел провернуться, вернулось.
     */
    var isRefreshing: Boolean
        get() = refreshing
        set(v) {
            if (v == refreshing) { if (v) removeCallbacks(finish); return }
            removeCallbacks(finish)
            if (v) {
                refreshing = true
                shownAt = SystemClock.uptimeMillis()
                animateTo(gap)
            } else {
                val wait = MIN_SHOW_MS - (SystemClock.uptimeMillis() - shownAt)
                if (wait > 0) postDelayed(finish, wait) else finish.run()
            }
        }

    override fun onDetachedFromWindow() {
        removeCallbacks(finish)
        super.onDetachedFromWindow()
    }

    private companion object { const val MIN_SHOW_MS = 450L }

    init {
        addView(spinner, LayoutParams(size, size, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = pad.toInt() })
    }

    fun setOnRefreshListener(l: () -> Unit) { listener = l }

    fun setColorSchemeResources(res: Int) {
        spinner.indeterminateTintList = ColorStateList.valueOf(context.getColor(res))
    }

    private fun target(): View? {
        for (i in 0 until childCount) { val v = getChildAt(i); if (v !== spinner) return v }
        return null
    }

    private fun setOffset(v: Float) {
        offset = v
        target()?.translationY = v
        // при оттягивании дальше нормы индикатор остаётся по центру освободившегося места
        spinner.translationY = ((v - gap) / 2f).coerceAtLeast(0f)
        spinner.alpha = (v / gap).coerceIn(0f, 1f)
        spinner.visibility = if (v > 0.5f) View.VISIBLE else View.INVISIBLE
    }

    private fun animateTo(to: Float) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(offset, to).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener { setOffset(it.animatedValue as Float) }
            start()
        }
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { startX = e.x; startY = e.y; dragging = false }
            MotionEvent.ACTION_MOVE -> if (!dragging && !isRefreshing && isEnabled) {
                val dy = e.y - startY
                val dx = e.x - startX
                // тянем вниз, движение в основном вертикальное, а список уже в самом верху
                if (dy > slop && dy > Math.abs(dx) && target()?.canScrollVertically(-1) == false) {
                    dragging = true
                    startY = e.y
                    anim?.cancel()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return dragging
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> setOffset(((e.y - startY) * 0.55f).coerceIn(0f, gap * 1.6f))
            MotionEvent.ACTION_UP -> {
                dragging = false
                if (offset >= gap) { isRefreshing = true; listener?.invoke() } else animateTo(0f)
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; animateTo(if (isRefreshing) gap else 0f) }
        }
        return true
    }
}
