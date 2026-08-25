package com.presenceflipclock

import android.content.Context

/** Tiny SharedPreferences wrapper for user settings. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("presence_clock", Context.MODE_PRIVATE)

    /** 24-hour clock (true) vs 12-hour (false). */
    var use24h: Boolean
        get() = sp.getBoolean("use24h", true)
        set(v) { sp.edit().putBoolean("use24h", v).apply() }

    /** Seconds before the clock dims when no motion is seen. */
    var idleTimeoutSec: Int
        get() = sp.getInt("idleTimeoutSec", 30)
        set(v) { sp.edit().putInt("idleTimeoutSec", v.coerceIn(5, 600)).apply() }

    /** 1 (needs big movement) .. 10 (very sensitive). */
    var sensitivity: Int
        get() = sp.getInt("sensitivity", 5)
        set(v) { sp.edit().putInt("sensitivity", v.coerceIn(1, 10)).apply() }

    /** Brightness (0..100 %) the screen dims to when idle. */
    var dimPercent: Int
        get() = sp.getInt("dimPercent", 2)
        set(v) { sp.edit().putInt("dimPercent", v.coerceIn(0, 60)).apply() }
}
