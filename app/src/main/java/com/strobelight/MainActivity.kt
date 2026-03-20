package com.strobelight

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    // ── UI ───────────────────────────────────────────────────────────────────
    private lateinit var tvFrequency:   TextView
    private lateinit var tvPeriod:      TextView
    private lateinit var tvStatus:      TextView
    private lateinit var tvDutyPct:     TextView
    private lateinit var seekBar:       SeekBar
    private lateinit var seekDuty:      SeekBar
    private lateinit var switchStrobe:  Switch
    private lateinit var etExactFps:    EditText
    private lateinit var btnSet:        Button
    private lateinit var btnScreenMode: Button
    private lateinit var strobeView:    StrobeView
    private lateinit var btnPreset1:    Button
    private lateinit var btnPreset10:   Button
    private lateinit var btnPreset60:   Button
    private lateinit var btnPreset120:  Button
    private lateinit var btnPreset1k:   Button
    private lateinit var btnPreset20k:  Button

    // ── Flash state ──────────────────────────────────────────────────────────
    private var cameraId: String? = null
    private lateinit var cameraManager: CameraManager

    private lateinit var flashThread: HandlerThread
    private lateinit var flashHandler: Handler
    private val uiHandler = Handler(android.os.Looper.getMainLooper())

    private var isStrobing  = false
    private var currentFps  = 10.0
    private var dutyCycle   = 0.5f

    private val MIN_FPS  = 1.0
    private val MAX_FPS  = 20_000.0
    private val SEEK_MAX = 1000

    // ── Strobe runnable (runs on flashThread) ────────────────────────────────
    private val strobeRunnable = object : Runnable {
        override fun run() {
            if (!isStrobing) return
            val periodMs = 1000.0 / currentFps
            val onMs     = (periodMs * dutyCycle).toLong().coerceAtLeast(1L)
            val offMs    = (periodMs * (1.0 - dutyCycle)).toLong().coerceAtLeast(1L)

            setFlash(true)
            uiHandler.post { strobeView.pulse() }

            flashHandler.postDelayed({
                if (!isStrobing) return@postDelayed
                setFlash(false)
                flashHandler.postDelayed(this, offMs)
            }, onMs)
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        bindViews()
        setupFlashThread()
        setupCamera()
        setupSeekBar()
        setupDutySeekBar()
        setupSwitch()
        setupExactInput()
        setupPresets()
        setupScreenMode()
        updateUI(currentFps)
    }

    private fun bindViews() {
        tvFrequency   = findViewById(R.id.tvFrequency)
        tvPeriod      = findViewById(R.id.tvPeriod)
        tvStatus      = findViewById(R.id.tvStatus)
        tvDutyPct     = findViewById(R.id.tvDutyPct)
        seekBar       = findViewById(R.id.seekBar)
        seekDuty      = findViewById(R.id.seekDuty)
        switchStrobe  = findViewById(R.id.switchStrobe)
        etExactFps    = findViewById(R.id.etExactFps)
        btnSet        = findViewById(R.id.btnSet)
        btnScreenMode = findViewById(R.id.btnScreenMode)
        strobeView    = findViewById(R.id.strobeView)
        btnPreset1    = findViewById(R.id.btnPreset1)
        btnPreset10   = findViewById(R.id.btnPreset10)
        btnPreset60   = findViewById(R.id.btnPreset60)
        btnPreset120  = findViewById(R.id.btnPreset120)
        btnPreset1k   = findViewById(R.id.btnPreset1k)
        btnPreset20k  = findViewById(R.id.btnPreset20k)
    }

    private fun setupFlashThread() {
        flashThread = HandlerThread("FlashThread", Process.THREAD_PRIORITY_URGENT_DISPLAY)
        flashThread.start()
        flashHandler = Handler(flashThread.looper)
    }

    // ── Camera ───────────────────────────────────────────────────────────────
    private fun setupCamera() {
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
            return
        }
        initCameraId()
    }

    private fun initCameraId() {
        try {
            for (id in cameraManager.cameraIdList) {
                val c = cameraManager.getCameraCharacteristics(id)
                if (c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                    cameraId = id; break
                }
            }
        } catch (e: CameraAccessException) {
            tvStatus.text = "⚠ Camera error"
        }
        if (cameraId == null) tvStatus.text = "⚠ No torch — use Screen Mode"
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.getOrNull(0) == PackageManager.PERMISSION_GRANTED)
            initCameraId()
        else
            tvStatus.text = "⚠ Camera permission denied"
    }

    // ── FPS SeekBar (log scale) ──────────────────────────────────────────────
    private fun setupSeekBar() {
        seekBar.max = SEEK_MAX
        seekBar.progress = fpsToProgress(currentFps)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) { currentFps = progressToFps(p); updateUI(currentFps) }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun progressToFps(p: Int) =
        MIN_FPS * Math.pow(MAX_FPS / MIN_FPS, p.toDouble() / SEEK_MAX)

    private fun fpsToProgress(fps: Double) =
        (Math.log(fps / MIN_FPS) / Math.log(MAX_FPS / MIN_FPS) * SEEK_MAX)
            .roundToInt().coerceIn(0, SEEK_MAX)

    // ── Duty-cycle SeekBar (5%–95%) ──────────────────────────────────────────
    private fun setupDutySeekBar() {
        seekDuty.max = 90
        seekDuty.progress = ((dutyCycle * 100) - 5).toInt()
        tvDutyPct.text = "50%"
        seekDuty.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    dutyCycle = ((p + 5) / 100f).coerceIn(0.05f, 0.95f)
                    tvDutyPct.text = "%.0f%%".format(dutyCycle * 100)
                    strobeView.setDutyCycle(dutyCycle)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    // ── Switch ───────────────────────────────────────────────────────────────
    private fun setupSwitch() {
        switchStrobe.setOnCheckedChangeListener { _, checked ->
            if (checked) startStrobe() else stopStrobe()
        }
    }

    // ── Exact FPS input ──────────────────────────────────────────────────────
    private fun setupExactInput() {
        btnSet.setOnClickListener {
            val v = etExactFps.text.toString().toDoubleOrNull()
            if (v != null && v in MIN_FPS..MAX_FPS) {
                applyFps(v); etExactFps.text.clear(); etExactFps.clearFocus()
            } else {
                Toast.makeText(this, "Enter 1 – 20,000", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Screen mode ──────────────────────────────────────────────────────────
    private fun setupScreenMode() {
        btnScreenMode.setOnClickListener {
            if (isStrobing) switchStrobe.isChecked = false
            startActivity(
                Intent(this, ScreenStrobeActivity::class.java)
                    .putExtra(ScreenStrobeActivity.EXTRA_FPS,  currentFps)
                    .putExtra(ScreenStrobeActivity.EXTRA_DUTY, dutyCycle)
            )
        }
    }

    // ── Presets ──────────────────────────────────────────────────────────────
    private fun setupPresets() {
        mapOf(
            btnPreset1   to 1.0,
            btnPreset10  to 10.0,
            btnPreset60  to 60.0,
            btnPreset120 to 120.0,
            btnPreset1k  to 1_000.0,
            btnPreset20k to 20_000.0
        ).forEach { (btn, fps) -> btn.setOnClickListener { applyFps(fps) } }
    }

    private fun applyFps(fps: Double) {
        currentFps = fps
        seekBar.progress = fpsToProgress(fps)
        updateUI(fps)
    }

    // ── Strobe start / stop ──────────────────────────────────────────────────
    private fun startStrobe() {
        if (cameraId == null) {
            switchStrobe.isChecked = false
            Toast.makeText(this, "No torch — tap SCREEN MODE", Toast.LENGTH_LONG).show()
            return
        }
        isStrobing = true
        tvStatus.text = "● STROBING"
        strobeView.setActive(true)
        flashHandler.post(strobeRunnable)
    }

    private fun stopStrobe() {
        isStrobing = false
        flashHandler.removeCallbacksAndMessages(null)
        setFlash(false)
        tvStatus.text = "○ STANDBY"
        strobeView.setActive(false)
    }

    private fun setFlash(on: Boolean) {
        try { cameraId?.let { cameraManager.setTorchMode(it, on) } }
        catch (_: CameraAccessException) {}
    }

    // ── UI refresh ───────────────────────────────────────────────────────────
    private fun updateUI(fps: Double) {
        tvFrequency.text = when {
            fps >= 1000 -> "${"%.2f".format(fps / 1000)} k"
            fps == fps.roundToInt().toDouble() -> "%.0f".format(fps)
            else -> "%.2f".format(fps)
        }
        val periodUs = 1_000_000.0 / fps
        tvPeriod.text = when {
            periodUs >= 1_000_000 -> "Period  ${"%.3f".format(periodUs / 1_000_000)} s"
            periodUs >= 1_000     -> "Period  ${"%.3f".format(periodUs / 1_000)} ms"
            else                  -> "Period  ${"%.2f".format(periodUs)} µs"
        }
        strobeView.setFrequency(fps)
        etExactFps.hint = if (fps == fps.roundToInt().toDouble()) "%.0f".format(fps)
                          else "%.2f".format(fps)
    }

    // ────────────────────────────────────────────────────────────────────────
    override fun onPause() {
        super.onPause()
        if (isStrobing) switchStrobe.isChecked = false
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStrobe()
        flashThread.quitSafely()
    }
}
