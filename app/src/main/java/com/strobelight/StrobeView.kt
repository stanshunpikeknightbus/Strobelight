package com.strobelight

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * Draws an animated square-wave visualizer that pulses in sync with the strobe.
 * Call [pulse] each time the flash fires, and [setFrequency] when FPS changes.
 */
class StrobeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // ── Paints ───────────────────────────────────────────────────────────────
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.SQUARE
        strokeJoin = Paint.Join.MITER
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.SQUARE
        strokeJoin = Paint.Join.MITER
        maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF667080.toInt()
        textSize = 28f
        typeface = Typeface.MONOSPACE
    }
    private val centerLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x22FFFFFF
        strokeWidth = 1f
    }

    // ── State ────────────────────────────────────────────────────────────────
    private var currentFps: Double = 10.0
    private var dutyCycle: Float = 0.5f          // 0..1
    private var isActive: Boolean = false
    private var pulseAlpha: Float = 0f            // 0..1, decays each frame
    private val waveColor = 0xFF00E5FF.toInt()    // cyan

    // scrolling waveform buffer
    private val SEGMENTS = 60
    private val waveState = BooleanArray(SEGMENTS) { false }   // true = HIGH
    private var waveHead = 0

    private val path = Path()

    // ── Public API ───────────────────────────────────────────────────────────
    fun setFrequency(fps: Double) { currentFps = fps; invalidate() }
    fun setDutyCycle(dc: Float)   { dutyCycle = dc;   invalidate() }
    fun setActive(active: Boolean) {
        isActive = active
        if (!active) { waveState.fill(false); pulseAlpha = 0f }
        invalidate()
    }

    /** Call whenever the flash fires HIGH */
    fun pulse() {
        pulseAlpha = 1f
        waveHead = (waveHead + 1) % SEGMENTS
        waveState[waveHead] = true
        val capturedHead = waveHead   // capture immutable copy for the lambda
        postDelayed({
            waveState[capturedHead] = false
            invalidate()
        }, (1000.0 / currentFps * dutyCycle).toLong().coerceAtLeast(16))
        invalidate()
    }

    // ── Draw ─────────────────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val midY = h / 2f
        val highY = h * 0.15f
        val lowY  = h * 0.85f

        // Decay glow
        if (pulseAlpha > 0f) {
            pulseAlpha = (pulseAlpha - 0.06f).coerceAtLeast(0f)
            if (pulseAlpha > 0f) postInvalidateOnAnimation()
        }

        // Center dim line
        canvas.drawLine(0f, midY, w, midY, centerLinePaint)

        // Build square-wave path from waveState ring-buffer
        val segW = w / SEGMENTS
        path.reset()
        var curHigh = waveState[(waveHead + 1) % SEGMENTS]
        path.moveTo(0f, if (curHigh) highY else lowY)

        for (i in 0 until SEGMENTS) {
            val idx  = (waveHead + 1 + i) % SEGMENTS
            val high = waveState[idx]
            val x    = i * segW

            if (high != curHigh) {
                // vertical edge
                path.lineTo(x, if (high) highY else lowY)
                curHigh = high
            }
            // horizontal run
            path.lineTo(x + segW, if (curHigh) highY else lowY)
        }

        val alpha = if (isActive) 255 else 80
        val glowA = (pulseAlpha * 200).toInt()

        // Glow layer
        if (glowA > 0) {
            glowPaint.color = (waveColor and 0x00FFFFFF) or (glowA shl 24)
            canvas.drawPath(path, glowPaint)
        }

        // Main wave
        wavePaint.color = (waveColor and 0x00FFFFFF) or (alpha shl 24)
        canvas.drawPath(path, wavePaint)

        // Frequency label
        val label = when {
            currentFps >= 1000 -> "${"%.1f".format(currentFps / 1000)} kHz"
            else               -> "${"%.1f".format(currentFps)} Hz"
        }
        canvas.drawText(label, 20f, h - 14f, labelPaint)

        // Duty label
        val dutyLabel = "DC: ${"%.0f".format(dutyCycle * 100)}%"
        val dw = labelPaint.measureText(dutyLabel)
        canvas.drawText(dutyLabel, w - dw - 20f, h - 14f, labelPaint)
    }
}
