package com.presenceflipclock

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** HH : MM built from four [FlipDigitView]s and a colon. Sizes scale to the screen. */
class FlipClockView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val digits = ArrayList<FlipDigitView>(4)
    private val colon = TextView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        addDigit(); addDigit()
        colon.text = ":"
        colon.setTextColor(Color.parseColor("#EDEDED"))
        colon.gravity = Gravity.CENTER
        colon.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        addView(colon)
        addDigit(); addDigit()
        applySize()
    }

    private fun addDigit() {
        val v = FlipDigitView(context)
        digits.add(v)
        addView(v)
    }

    /** Recompute card + text sizes so HH:MM always fits the screen width, capped by height. */
    fun applySize() {
        val dm = resources.displayMetrics
        // width-driven so four cards + gaps + colon fit across the screen (~90% of width)
        var cardW = dm.widthPixels * 0.17f
        var cardH = cardW / 0.62f
        // don't let the cards get taller than half the screen (landscape / short screens)
        val maxH = dm.heightPixels * 0.5f
        if (cardH > maxH) { cardH = maxH; cardW = cardH * 0.62f }
        val gap = (cardW * 0.10f).toInt()
        val textPx = cardH * 0.62f

        for (d in digits) {
            val lp = LayoutParams(cardW.toInt(), cardH.toInt())
            lp.marginStart = gap; lp.marginEnd = gap
            d.layoutParams = lp
            d.setTextSizePx(textPx)
        }
        colon.setTextSize(TypedValue.COMPLEX_UNIT_PX, cardH * 0.5f)
        requestLayout()
    }

    fun setTime(hh: Int, mm: Int, animate: Boolean) {
        val s = "%02d%02d".format(hh, mm)
        for (i in 0 until 4) digits[i].setChar(s[i], animate)
    }
}
