package com.presenceflipclock

import android.Manifest
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.presenceflipclock.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

/**
 * A presence-aware flip clock for a NON-ROOTED phone.
 *
 * Instead of switching the screen power state (which needs root, or DeviceAdmin plus a
 * background camera that Android restricts), the activity stays foreground with the screen
 * kept on and simply modulates *window brightness*: full when the front camera sees motion,
 * near-black when the room is still. Window brightness is app-local and needs no permission,
 * and because the app stays foreground the camera keeps working on every Android version.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private var lastMotion = SystemClock.elapsedRealtime()
    private var bright = true
    private var brightnessAnim: ValueAnimator? = null
    private var lastMinuteShift = -1

    private val tickHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            checkIdle()
            tickHandler.postDelayed(this, 1000L)
        }
    }

    private val requestCamera = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else b.hint.apply {
            text = getString(R.string.no_camera_hint)
            visibility = View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Keep the screen on, show over the lock screen (desk/dock use), stay fullscreen.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        hideSystemBars()

        // Tap anywhere = wake (works even without camera permission).
        b.root.setOnClickListener { onMotion() }
        b.root.setOnLongClickListener { showSettings(); true }

        setBrightness(1f, animate = false)
        b.clock.applySize()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        updateClock(animate = false)
        tickHandler.post(tick)
        ensureCamera()
        onMotion()
    }

    override fun onPause() {
        super.onPause()
        tickHandler.removeCallbacks(tick)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        b.clock.applySize()
    }

    // ---- clock ----------------------------------------------------------------

    private fun updateClock(animate: Boolean = true) {
        val c = Calendar.getInstance()
        var h = c.get(Calendar.HOUR_OF_DAY)
        if (!prefs.use24h) {
            h %= 12
            if (h == 0) h = 12
        }
        b.clock.setTime(h, c.get(Calendar.MINUTE), animate)

        val datePat = if (prefs.use24h) "EEE, d MMM" else "EEE, d MMM  a"
        b.date.text = SimpleDateFormat(datePat, Locale.getDefault()).format(c.time)

        // gentle per-minute pixel shift to spread OLED wear
        val minute = c.get(Calendar.MINUTE)
        if (minute != lastMinuteShift) {
            lastMinuteShift = minute
            val d = resources.displayMetrics.density
            b.content.translationX = ((minute % 5) - 2) * 2f * d
            b.content.translationY = ((minute / 12) - 2) * 2f * d
        }
    }

    // ---- presence / brightness ------------------------------------------------

    private fun onMotion() {
        lastMotion = SystemClock.elapsedRealtime()
        if (!bright) setBrightness(1f, animate = true).also { bright = true }
    }

    private fun checkIdle() {
        val idleMs = prefs.idleTimeoutSec * 1000L
        if (bright && SystemClock.elapsedRealtime() - lastMotion > idleMs) {
            bright = false
            setBrightness(prefs.dimPercent / 100f, animate = true)
        }
    }

    private fun setBrightness(target: Float, animate: Boolean): Unit {
        val clamped = target.coerceIn(0f, 1f)   // 0f = fully dark (near-off on OLED)
        android.util.Log.d("PresenceClock", "brightness -> %.2f (animate=%b)".format(clamped, animate))
        val lp = window.attributes
        val from = if (lp.screenBrightness < 0f) 1f else lp.screenBrightness
        brightnessAnim?.cancel()
        if (!animate) {
            lp.screenBrightness = clamped
            window.attributes = lp
            return
        }
        brightnessAnim = ValueAnimator.ofFloat(from, clamped).apply {
            duration = 450
            addUpdateListener {
                val a = window.attributes
                a.screenBrightness = it.animatedValue as Float
                window.attributes = a
            }
            start()
        }
    }

    // ---- camera ---------------------------------------------------------------

    private fun ensureCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera()
        else requestCamera.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        b.hint.visibility = View.GONE
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(320, 240))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(
                    cameraExecutor,
                    MotionAnalyzer(
                        onMotion = { runOnUiThread { onMotion() } },
                        sensitivity = { prefs.sensitivity }
                    )
                )
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            } catch (e: Exception) {
                b.hint.apply {
                    text = getString(R.string.no_camera_hint)
                    visibility = View.VISIBLE
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---- settings (long-press) ------------------------------------------------

    private fun showSettings() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        fun label(t: String) = TextView(this).apply { text = t; setPadding(0, pad, 0, 0) }

        box.addView(label(getString(R.string.set_format)))
        val fmt = android.widget.Switch(this).apply {
            text = getString(R.string.set_24h); isChecked = prefs.use24h
        }
        box.addView(fmt)

        box.addView(label(getString(R.string.set_sensitivity)))
        val sens = seek(1, 10, prefs.sensitivity); box.addView(sens)

        box.addView(label(getString(R.string.set_timeout)))
        val to = seek(5, 300, prefs.idleTimeoutSec); box.addView(to)

        box.addView(label(getString(R.string.set_dim)))
        val dim = seek(0, 60, prefs.dimPercent); box.addView(dim)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                prefs.use24h = fmt.isChecked
                prefs.sensitivity = sens.progressToValue(1)
                prefs.idleTimeoutSec = to.progressToValue(5)
                prefs.dimPercent = dim.progressToValue(0)
                updateClock(animate = false)
                onMotion()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun seek(min: Int, max: Int, value: Int): SeekBar = SeekBar(this).apply {
        this.max = max - min
        progress = (value - min).coerceIn(0, this.max)
        tag = min
    }

    private fun SeekBar.progressToValue(min: Int): Int = progress + min

    // ---- system bars ----------------------------------------------------------

    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }
}
