package com.r57zone.podcasteasy

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Область обложки/видео: всегда на всю ширину; высота — ширина (квадрат), а если места по высоте меньше,
 * то сколько есть (получается широкий прямоугольник, картинка обрезается по краям, не искажаясь).
 */
class SquareLayout @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : FrameLayout(ctx, attrs) {
    override fun onMeasure(w: Int, h: Int) {
        val ws = MeasureSpec.getSize(w)
        val hs = MeasureSpec.getSize(h)
        val side = if (MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED || hs == 0) ws else minOf(ws, hs)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(ws, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY)
        )
    }
}
