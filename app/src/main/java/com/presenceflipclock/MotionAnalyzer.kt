package com.presenceflipclock

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.abs

/**
 * Detects motion from the camera's luminance plane with a cheap downsampled frame diff -
 * the same idea as the Mi-A2 motion_diff, but running in-process on the Y plane so it is
 * fast enough to run continuously on an old phone.
 *
 * Privacy: only the luma (brightness) plane is read into a tiny grid in memory. No frame
 * is decoded to a bitmap, saved, or transmitted. Nothing leaves this function.
 */
class MotionAnalyzer(
    private val onMotion: () -> Unit,
    private val sensitivity: () -> Int   // 1..10
) : ImageAnalysis.Analyzer {

    private val gridW = 32
    private val gridH = 24
    private var prev: IntArray? = null
    private var frameCount = 0

    // Below this mean luminance the scene is effectively dark (covered lens, lights off,
    // face-down). Dark frames are dominated by sensor NOISE, whose frame-to-frame diff can
    // exceed the motion threshold and falsely read as constant motion - which kept the clock
    // awake when the camera was covered. A dark scene cannot show presence anyway, so treat
    // it as "no motion" and let the idle timer dim the clock.
    private val darkLumaFloor = 12

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]           // Y (luminance) for YUV_420_888
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val w = image.width
            val h = image.height
            if (w == 0 || h == 0) return

            val cur = IntArray(gridW * gridH)
            for (gy in 0 until gridH) {
                val sy = gy * h / gridH
                val rowBase = sy * rowStride
                for (gx in 0 until gridW) {
                    val sx = gx * w / gridW
                    val idx = rowBase + sx * pixelStride
                    cur[gy * gridW + gx] = if (idx in 0 until buf.limit()) buf.get(idx).toInt() and 0xFF else 0
                }
            }

            var sum = 0L
            for (v in cur) sum += v
            val luma = sum.toDouble() / cur.size          // overall scene brightness

            val p = prev
            var meanDiff = 0.0
            var fired = false
            if (p != null && luma >= darkLumaFloor) {      // skip motion in a dark/covered scene
                var diff = 0L
                for (i in cur.indices) diff += abs(cur[i] - p[i])
                meanDiff = diff / cur.size.toDouble()
                // sensitivity 1 -> threshold ~14 (big motion), 10 -> ~3 (twitchy)
                val threshold = 14.0 - (sensitivity().coerceIn(1, 10) - 1) * 1.2
                if (meanDiff > threshold) { onMotion(); fired = true }
            }
            prev = cur

            // light heartbeat (~every 10s at 30fps): filter logcat with tag PresenceClock
            if (frameCount++ % 300 == 0) {
                Log.d("PresenceClock", "luma=%.1f diff=%.1f dark=%b motion=%b".format(
                    luma, meanDiff, luma < darkLumaFloor, fired))
            }
        } catch (_: Throwable) {
            // never let a bad frame crash the clock
        } finally {
            image.close()
        }
    }
}
