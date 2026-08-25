package com.presenceflipclock

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

            val p = prev
            if (p != null) {
                var diff = 0L
                for (i in cur.indices) diff += abs(cur[i] - p[i])
                val mean = diff.toDouble() / cur.size
                // sensitivity 1 -> threshold ~14 (big motion), 10 -> ~3 (twitchy)
                val threshold = 14.0 - (sensitivity().coerceIn(1, 10) - 1) * 1.2
                if (mean > threshold) onMotion()
            }
            prev = cur
        } catch (_: Throwable) {
            // never let a bad frame crash the clock
        } finally {
            image.close()
        }
    }
}
