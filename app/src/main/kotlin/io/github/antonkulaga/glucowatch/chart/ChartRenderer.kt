package io.github.antonkulaga.glucowatch.chart

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import io.github.antonkulaga.glucowatch.data.GlucoseState
import kotlin.math.max
import kotlin.math.min

/** Draws history dots, the target range and the optional forecast into a bitmap. */
object ChartRenderer {
    const val COLOR_LOW = 0xFFFF5252.toInt()
    const val COLOR_HIGH = 0xFFFFB300.toInt()
    const val COLOR_IN_RANGE = 0xFF66BB6A.toInt()
    private const val COLOR_BAND = 0x3366BB6A
    private const val COLOR_FORECAST = 0xFFB388FF.toInt()
    private const val COLOR_FORECAST_BAND = 0x40B388FF
    private const val COLOR_GRID = 0x66FFFFFF
    private const val COLOR_LABEL = 0xB3FFFFFF.toInt()

    fun colorFor(mgdl: Double, state: GlucoseState): Int = when {
        mgdl < state.settings.lowMgdl -> COLOR_LOW
        mgdl > state.settings.highMgdl -> COLOR_HIGH
        else -> COLOR_IN_RANGE
    }

    fun render(state: GlucoseState, width: Int, height: Int, labels: Boolean = true, now: Long = System.currentTimeMillis()): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = state.settings
        val forecast = state.prediction?.points.orEmpty()

        val start = now - s.chartHours * 3_600_000L
        val end = now + if (forecast.isNotEmpty()) s.horizonMinutes * 60_000L else 5 * 60_000L
        val visible = state.readings.filter { it.timeMillis in start..end }

        val values = visible.map { it.mgdl.toDouble() } + forecast.mapNotNull { it.upper ?: it.mgdl } + forecast.mapNotNull { it.lower ?: it.mgdl }
        val yMin = min(60.0, (values.minOrNull() ?: 60.0) - 10).coerceAtLeast(39.0)
        val yMax = max(220.0, (values.maxOrNull() ?: 220.0) + 10).coerceAtMost(401.0)

        val textSize = height * 0.1f
        val left = if (labels) textSize * 2.2f else 2f
        val bottom = if (labels) height - textSize * 1.2f else height - 2f
        val plot = RectF(left, 4f, width - 4f, bottom)

        fun x(t: Long) = plot.left + (t - start).toFloat() / (end - start) * plot.width()
        fun y(v: Double) = plot.bottom - ((v - yMin) / (yMax - yMin)).toFloat() * plot.height()

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Target range band.
        paint.color = COLOR_BAND
        c.drawRect(plot.left, y(s.highMgdl.toDouble()), plot.right, y(s.lowMgdl.toDouble()), paint)

        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_LABEL; this.textSize = textSize }
        if (labels) {
            label.textAlign = Paint.Align.RIGHT
            listOf(s.lowMgdl, s.highMgdl).forEach { v ->
                c.drawText(s.unit.format(v.toDouble()), plot.left - textSize * 0.3f, y(v.toDouble()) + textSize * 0.35f, label)
            }
            label.textAlign = Paint.Align.CENTER
            for (h in s.chartHours downTo 1) {
                val t = now - h * 3_600_000L
                c.drawText("-${h}h", x(t), height - textSize * 0.2f, label)
            }
        }

        // "Now" marker.
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_GRID; strokeWidth = max(1f, width / 300f)
            pathEffect = DashPathEffect(floatArrayOf(4f, 6f), 0f)
        }
        c.drawLine(x(now), plot.top, x(now), plot.bottom, grid)

        // Forecast band + dashed line.
        if (forecast.isNotEmpty() && visible.isNotEmpty()) {
            val lastReading = visible.last()
            if (forecast.all { it.lower != null && it.upper != null }) {
                val band = Path().apply {
                    moveTo(x(lastReading.timeMillis), y(lastReading.mgdl.toDouble()))
                    forecast.forEach { lineTo(x(it.timeMillis), y(it.upper!!)) }
                    forecast.asReversed().forEach { lineTo(x(it.timeMillis), y(it.lower!!)) }
                    close()
                }
                paint.color = COLOR_FORECAST_BAND
                c.drawPath(band, paint)
            }
            val line = Path().apply {
                moveTo(x(lastReading.timeMillis), y(lastReading.mgdl.toDouble()))
                forecast.forEach { lineTo(x(it.timeMillis), y(it.mgdl)) }
            }
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; color = COLOR_FORECAST; strokeWidth = max(2f, width / 120f)
                pathEffect = DashPathEffect(floatArrayOf(width / 45f, width / 70f), 0f)
            }
            c.drawPath(line, stroke)
        }

        // Readings as dots, coloured by range.
        val r = max(2f, min(width / 110f, plot.height() / 25f))
        visible.forEach {
            paint.color = colorFor(it.mgdl.toDouble(), state)
            c.drawCircle(x(it.timeMillis), y(it.mgdl.toDouble()), r, paint)
        }

        if (visible.isEmpty()) {
            label.textAlign = Paint.Align.CENTER
            c.drawText("no data", plot.centerX(), plot.centerY(), label)
        }
        return bmp
    }
}
