package com.presenceflipclock

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * One digit rendered as a dark flip card (rounded background + centre divider line).
 * A digit change plays a short vertical flip so it reads like a mechanical flip clock,
 * without the complexity (and crash surface) of splitting the glyph into two halves.
 */
class FlipDigitView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val tv = TextView(context)
    private var current: Char = ' '

    init {
        background = ContextCompat.getDrawable(context, R.drawable.flip_card)
        tv.gravity = Gravity.CENTER
        tv.setTextColor(Color.parseColor("#EDEDED"))
        tv.includeFontPadding = false
        tv.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        addView(tv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val divider = View(context)
        divider.setBackgroundColor(Color.parseColor("#55000000"))
        val dp2 = (2 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        addView(divider, LayoutParams(LayoutParams.MATCH_PARENT, dp2, Gravity.CENTER_VERTICAL))
    }

    fun setTextSizePx(px: Float) = tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)

    fun setChar(c: Char, animate: Boolean) {
        if (c == current) return
        current = c
        if (!animate || tv.width == 0) {
            tv.text = c.toString()
            return
        }
        tv.cameraDistance = 12000f * resources.displayMetrics.density
        tv.animate().rotationX(-90f).setDuration(110).withEndAction {
            tv.text = c.toString()
            tv.rotationX = 90f
            tv.animate().rotationX(0f).setDuration(110).start()
        }.start()
    }
}
