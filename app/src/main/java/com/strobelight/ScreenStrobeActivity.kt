package com.strobelight

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.View
import android.view.WindowManager

/**
 * Full-screen screen strobe — alternates between black and white.
 * Launched from MainActivity when user taps "Screen Mode".
 * Pass fps as EXTRA_FPS and duty cycle as EXTRA_DUTY.
 */
class ScreenStrobeActivity : Activity() {

    companion object {
        const val EXTRA_FPS  = "fps"
        const val EXTRA_DUTY = "duty"
    }

    private lateinit var flashView: View
    private lateinit var strobeThread: HandlerThread
    private lateinit var strobeHandler: Handler
    private val uiHandler = Handler(android.os.Looper.getMainLooper())

    private var fps  = 10.0
    private var duty = 0.5f
    private var running = false

    private val strobeRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val periodMs   = 1000.0 / fps
            val onDuration = (periodMs * duty).toLong().coerceAtLeast(1L)
            val offDuration = (periodMs * (1 - duty)).toLong().coerceAtLeast(1L)

            // Flash ON
            uiHandler.post { flashView.setBackgroundColor(Color.WHITE) }
            strobeHandler.postDelayed({
                // Flash OFF
                uiHandler.post { flashView.setBackgroundColor(Color.BLACK) }
                strobeHandler.postDelayed(this, offDuration)
            }, onDuration)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fps  = intent.getDoubleExtra(EXTRA_FPS,  10.0)
        duty = intent.getFloatExtra(EXTRA_DUTY,  0.5f)

        // Full-screen, max brightness
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        val lp = window.attributes
        lp.screenBrightness = 1f
        window.attributes = lp

        // Hide nav + status bars (immersive sticky)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        flashView = View(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        setContentView(flashView)

        // Tap anywhere to exit
        flashView.setOnClickListener { finish() }

        // Start strobe on a background thread for tighter timing
        strobeThread = HandlerThread("StrobeThread", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
        strobeThread.start()
        strobeHandler = Handler(strobeThread.looper)

        running = true
        strobeHandler.post(strobeRunnable)
    }

    override fun onDestroy() {
        running = false
        strobeHandler.removeCallbacksAndMessages(null)
        uiHandler.removeCallbacksAndMessages(null)
        strobeThread.quitSafely()
        flashView.setBackgroundColor(android.graphics.Color.BLACK)
        super.onDestroy()
    }
}
