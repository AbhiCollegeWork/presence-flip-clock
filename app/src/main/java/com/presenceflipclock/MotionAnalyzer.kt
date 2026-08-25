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
            val lim = buf.limit()
            for (gy in 0 until gridH) {
                val sy = gy * h / gridH
                val rowBase = sy * rowStride
                for (gx in 0 until gridW) {
                    val sx = gx * w / gridW
                    val idx = rowBase + sx * pixelStride
                    cur[gy * gridW + gx] = if (idx in 0 until lim) buf.get(idx).toInt() and 0xFF else 0
                }
            }

            var sum = 0L
            for (v in cur) sum += v
            val luma = sum.toDouble() / cur.size          // overall scene brightness

            val p = prev
            var changed = 0
            var fired = false
            if (p != null && luma >= darkLumaFloor) {      // skip motion in a dark/covered scene
                // Cancel the global brightness oscillation (mains-lighting flicker beats with the
                // camera and shifts the WHOLE frame every frame - the dominant false signal here).
                // Then count cells that changed beyond that shift = real, localized presence.
                var sumDelta = 0L
                for (i in cur.indices) sumDelta += (cur[i] - p[i])
                val globalShift = sumDelta.toDouble() / cur.size
                for (i in cur.indices) if (abs((cur[i] - p[i]) - globalShift) > 45) changed++
                // At delta 45 the static/flicker residual is ~0 cells, so it reliably dims;
                // real close movement produces several strongly-changed cells.
                // sensitivity 1 -> need 8 cells; 10 -> 2
                val minCells = (9 - sensitivity().coerceIn(1, 10)).coerceAtLeast(2)
                if (changed >= minCells) { onMotion(); fired = true }
            }
            prev = cur

            // light heartbeat (~every 5s at 30fps) for on-device diagnosis; tag PresenceClock
            if (frameCount++ % 150 == 0) {
                Log.d("PresenceClock", "luma=%.1f cells=%d dark=%b motion=%b".format(
                    luma, changed, luma < darkLumaFloor, fired))
            }
        } catch (_: Throwable) {
            // never let a bad frame crash the clock
        } finally {
            image.close()
        }
    }
}
