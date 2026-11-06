package com.example.stylustest

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * A simple dial that shows a roll value in degrees (0..360).
 *
 * The needle rotates based on [rollDegrees]. A value of null shows the dial
 * without a needle (e.g. while waiting for data).
 */
class RollDialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }

    private var rollDegrees: Float? = null
    private var label: String = "ROLL"

    fun setRoll(degrees: Float?) {
        rollDegrees = degrees
        invalidate()
    }

    fun setLabel(label: String) {
        this.label = label
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val radius = (coerceAtMost(w, h) / 2f - 16f)

        // Dial circle
        canvas.drawCircle(cx, cy, radius, dialPaint)

        // Tick marks every 30 degrees
        val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(80, 200, 200)
            strokeWidth = 2f
        }
        for (i in 0 until 12) {
            val angle = i * 30f
            val rad = Math.toRadians((angle - 90).toDouble())
            val x1 = cx + (radius - 10f) * cos(rad).toFloat()
            val y1 = cy + (radius - 10f) * sin(rad).toFloat()
            val x2 = cx + radius * cos(rad).toFloat()
            val y2 = cy + radius * sin(rad).toFloat()
            canvas.drawLine(x1, y1, x2, y2, tickPaint)
        }

        // Needle
        rollDegrees?.let { degrees ->
            val rad = Math.toRadians(((degrees % 360f + 360f) % 360f - 90).toDouble())
            val nx = cx + radius * 0.85f * cos(rad).toFloat()
            val ny = cy + radius * 0.85f * sin(rad).toFloat()
            canvas.drawLine(cx, cy, nx, ny, needlePaint)
        }

        // Label text
        canvas.drawText(label, cx, cy + radius + 32f, textPaint)

        // Value text
        val valueText = rollDegrees?.let { "%.0f°".format(it) } ?: "--°"
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(120, 255, 120)
            textSize = 32f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(valueText, cx, cy + radius + 72f, valuePaint)
    }

    private fun cos(r: Double): Float = kotlin.math.cos(r).toFloat()
    private fun sin(r: Double): Float = kotlin.math.sin(r).toFloat()
}
