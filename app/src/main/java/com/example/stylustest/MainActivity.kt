package com.example.stylustest

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(PenView(this))
    }
}

/** Draws the pen trail and prints every axis the device reports. */
class PenView(context: Context) : View(context) {

    private val dp = resources.displayMetrics.density
    private val path = Path()

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE
        strokeWidth = 3 * dp; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(120, 255, 120); textSize = 13 * dp; typeface = Typeface.MONOSPACE
    }
    private val dialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN; style = Paint.Style.STROKE; strokeWidth = 4 * dp
    }
    private val dialText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN; textSize = 16 * dp; textAlign = Paint.Align.CENTER
    }

    private var lines: List<String> = listOf("Touch or hover with the pen...")
    private var rollDegrees: Float? = null
    private var lastBrake = Float.NaN

    init { setBackgroundColor(Color.BLACK) }

    override fun onTouchEvent(e: MotionEvent): Boolean { handle(e); return true }
    override fun onGenericMotionEvent(e: MotionEvent): Boolean { handle(e); return true } // hover

    private fun handle(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { path.reset(); path.moveTo(e.x, e.y) }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.historySize) path.lineTo(e.getHistoricalX(i), e.getHistoricalY(i))
                path.lineTo(e.x, e.y)
            }
        }

        val tool = when (e.getToolType(0)) {
            MotionEvent.TOOL_TYPE_STYLUS -> "STYLUS"
            MotionEvent.TOOL_TYPE_ERASER -> "ERASER"
            MotionEvent.TOOL_TYPE_FINGER -> "FINGER"
            MotionEvent.TOOL_TYPE_MOUSE -> "MOUSE"
            else -> "UNKNOWN"
        }
        val brake = e.getAxisValue(MotionEvent.AXIS_BRAKE)

        val out = ArrayList<String>()
        out += "action      : ${MotionEvent.actionToString(e.actionMasked)}"
        out += "tool        : $tool"
        out += "x, y        : %.0f, %.0f".format(e.x, e.y)
        out += "pressure    : %.3f".format(e.pressure)
        out += "tilt        : %.1f deg".format(Math.toDegrees(e.getAxisValue(MotionEvent.AXIS_TILT).toDouble()))
        out += "orientation : %.1f deg (azimuth)".format(Math.toDegrees(e.orientation.toDouble()))
        out += "distance    : %.2f".format(e.getAxisValue(MotionEvent.AXIS_DISTANCE))
        out += "BRAKE (roll): %.1f".format(brake)
        out += "buttons     : 0x%X".format(e.buttonState)

        val dev = e.device
        if (dev != null) {
            out += ""
            out += "--- all axes: ${dev.name} ---"
            for (r in dev.motionRanges) {
                out += "%-18s %9.3f  [%.1f .. %.1f]".format(
                    MotionEvent.axisToString(r.axis), e.getAxisValue(r.axis), r.min, r.max
                )
            }
            val br = dev.motionRanges.firstOrNull { it.axis == MotionEvent.AXIS_BRAKE }
            rollDegrees = if (br != null && br.max > 0f) brake / br.max * 360f else null
        }
        lines = out

        if (brake != lastBrake) {
            lastBrake = brake
            Log.d("PEN", "AXIS_BRAKE (roll) = $brake  tool=$tool")
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(path, strokePaint)

        var y = 24 * dp
        for (l in lines) { canvas.drawText(l, 12 * dp, y, textPaint); y += textPaint.textSize * 1.25f }

        // roll dial (bottom right)
        val r = 70 * dp
        val cx = width - r - 24 * dp
        val cy = height - r - 40 * dp
        canvas.drawCircle(cx, cy, r, dialPaint)
        rollDegrees?.let {
            val a = Math.toRadians((it - 90).toDouble())
            canvas.drawLine(cx, cy, cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat(), dialPaint)
        }
        canvas.drawText("ROLL", cx, cy + r + 24 * dp, dialText)
    }
}
