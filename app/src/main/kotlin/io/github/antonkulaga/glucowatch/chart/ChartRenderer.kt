package io.github.antonkulaga.glucowatch.chart

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ComposeShader
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import glucowatch.core.Treatment
import glucowatch.core.formatAmount
import io.github.antonkulaga.glucowatch.data.GlucoseState
import kotlin.math.max
import kotlin.math.min

/**
 * The glucose chart for the face and the app: a smooth line coloured by range over a faint
 * target band, the forecast as a dashed continuation, boluses as blue dots under the plot and
 * carbs as orange dots above it. The background is transparent, so it sits on any face.
 */
object ChartRenderer {
    const val COLOR_LOW = 0xFFF87171.toInt()
    const val COLOR_HIGH = 0xFFFBBF24.toInt()
    const val COLOR_IN_RANGE = 0xFF4ADE80.toInt()
    const val COLOR_INSULIN = 0xFF60A5FA.toInt()
    const val COLOR_CARBS = 0xFFFB923C.toInt()
    const val COLOR_FORECAST = 0xFFC4B5FD.toInt()
    private const val COLOR_BAND = 0x14FFFFFF
    private const val COLOR_GUIDE = 0x38FFFFFF
    private const val COLOR_GRID = 0x12FFFFFF
    private const val COLOR_LABEL = 0x8CFFFFFF.toInt()

    /** Readings further apart than this are not joined by the line. */
    private const val GAP_MS = 12 * 60_000L

    fun colorFor(mgdl: Double, state: GlucoseState): Int = when {
        mgdl < state.settings.lowMgdl -> COLOR_LOW
        mgdl > state.settings.highMgdl -> COLOR_HIGH
        else -> COLOR_IN_RANGE
    }

    /**
     * [edge]: the chart spans a round screen from rim to rim, as on the face. The plot then fills
     * the whole width, labels sit inside it, and nothing that matters (labels, the latest reading,
     * the forecast) comes closer than 7% to the sides, where the circle cuts the corners off.
     */
    fun render(
        state: GlucoseState, width: Int, height: Int, labels: Boolean = true, edge: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = state.settings
        val forecast = state.prediction?.points.orEmpty()
        val inset = if (edge) width * 0.07f else 0f

        val start = now - s.chartHours * 3_600_000L
        val end = now + if (forecast.isNotEmpty()) s.horizonMinutes * 60_000L else 10 * 60_000L
        val visible = state.readings.filter { it.timeMillis in start..end }
        val marks = state.treatments.filter { it.timeMillis in start..end }
        val boluses = marks.filter { it.insulin > 0 }
        val carbs = marks.filter { it.carbs > 0 }

        val values = visible.map { it.mgdl.toDouble() } + forecast.map { it.upper ?: it.mgdl } + forecast.map { it.lower ?: it.mgdl }
        val yMin = min(s.lowMgdl - 15.0, (values.minOrNull() ?: 60.0) - 12).coerceAtLeast(39.0)
        val yMax = max(s.highMgdl + 40.0, (values.maxOrNull() ?: 220.0) + 12).coerceAtMost(401.0)

        val textSize = height * 0.12f
        val stroke = max(2.5f, height / 60f)
        val dot = stroke * 1.25f
        val font = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_LABEL; this.textSize = textSize; typeface = font }

        // Rows: carbs above the plot, boluses and then the time axis below it. Empty rows take no space.
        val carbRow = if (carbs.isEmpty()) 0f else textSize * 1.25f
        val bolusRow = if (boluses.isEmpty()) 0f else textSize * 1.25f
        val axisRow = if (labels) textSize * 1.35f else 0f
        val left = when {
            edge -> 0f
            labels -> label.measureText(s.unit.format(s.highMgdl.toDouble())) + textSize * 0.5f
            else -> 2f
        }
        val right = if (edge) width - inset else width - stroke * 2.5f
        val plot = RectF(left, carbRow + stroke * 2, right, height - axisRow - bolusRow - stroke)

        fun x(t: Long) = plot.left + (t - start).toFloat() / (end - start) * plot.width()
        fun y(v: Double) = plot.bottom - ((v - yMin) / (yMax - yMin)).toFloat() * plot.height()
        val yHigh = y(s.highMgdl.toDouble())
        val yLow = y(s.lowMgdl.toDouble())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Hour grid and labels.
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_GRID; strokeWidth = max(1f, stroke / 2.5f) }
        label.textAlign = Paint.Align.CENTER
        for (h in s.chartHours downTo 1) {
            val gx = x(now - h * 3_600_000L)
            c.drawLine(gx, plot.top, gx, plot.bottom, grid)
            val half = label.measureText("-${h}h") / 2
            if (labels && gx - half >= inset && gx + half <= width - inset) c.drawText("-${h}h", gx, height - textSize * 0.3f, label)
        }

        // Target range: a faint band with thin dashed edges, labelled on the left.
        paint.color = COLOR_BAND
        c.drawRect(plot.left, yHigh, plot.right, yLow, paint)
        val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = COLOR_GUIDE; strokeWidth = max(1f, stroke / 2.5f)
            pathEffect = DashPathEffect(floatArrayOf(stroke * 1.5f, stroke * 1.5f), 0f)
        }
        c.drawLine(plot.left, yHigh, plot.right, yHigh, guide)
        c.drawLine(plot.left, yLow, plot.right, yLow, guide)
        if (labels && edge) {
            // Inside the plot, just above each guide, on a black outline so the line can pass behind.
            label.textAlign = Paint.Align.LEFT
            listOf(s.lowMgdl, s.highMgdl).forEach { v ->
                outlined(c, s.unit.format(v.toDouble()), inset, y(v.toDouble()) - textSize * 0.3f, label, COLOR_LABEL)
            }
        } else if (labels) {
            label.textAlign = Paint.Align.RIGHT
            listOf(s.lowMgdl, s.highMgdl).forEach { v ->
                c.drawText(s.unit.format(v.toDouble()), plot.left - textSize * 0.35f, y(v.toDouble()) + textSize * 0.35f, label)
            }
        }

        // Forecast: a soft cone if the model gives one, then a dashed line from the latest reading.
        val last = visible.lastOrNull()
        if (forecast.isNotEmpty() && last != null) {
            val from = PointF(x(last.timeMillis), y(last.mgdl.toDouble()))
            if (forecast.all { it.lower != null && it.upper != null }) {
                val cone = Path().apply {
                    moveTo(from.x, from.y)
                    forecast.forEach { lineTo(x(it.timeMillis), y(it.upper!!)) }
                    forecast.asReversed().forEach { lineTo(x(it.timeMillis), y(it.lower!!)) }
                    close()
                }
                paint.color = COLOR_FORECAST and 0x00FFFFFF or 0x2E000000
                c.drawPath(cone, paint)
            }
            val line = smooth(listOf(from) + forecast.map { PointF(x(it.timeMillis), y(it.mgdl)) })
            c.drawPath(line, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; color = COLOR_FORECAST; strokeWidth = stroke * 0.85f; strokeCap = Paint.Cap.ROUND
                pathEffect = DashPathEffect(floatArrayOf(stroke * 2.2f, stroke * 1.8f), 0f)
            })
        }

        // Readings: one smooth line per run without gaps, coloured by the band it passes through.
        // The glow under it takes the colour of the line above each column and fades downwards,
        // so a high line over the target band does not paint the band green.
        val runs = mutableListOf(mutableListOf<PointF>())
        visible.forEachIndexed { i, r ->
            if (i > 0 && r.timeMillis - visible[i - 1].timeMillis > GAP_MS) runs += mutableListOf<PointF>()
            runs.last() += PointF(x(r.timeMillis), y(r.mgdl.toDouble()))
        }
        val bands = listOf(Triple(0f, yHigh, COLOR_HIGH), Triple(yHigh, yLow, COLOR_IN_RANGE), Triple(yLow, height.toFloat(), COLOR_LOW))
        fun bandColor(py: Float) = bands.first { py < it.second || it === bands.last() }.third
        val lineStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        runs.filter { it.isNotEmpty() }.forEach { run ->
            val line = smooth(run)
            if (run.size > 1) {
                val area = Path(line).apply { lineTo(run.last().x, plot.bottom); lineTo(run.first().x, plot.bottom); close() }
                paint.shader = ComposeShader(
                    glowColors(run, yHigh, yLow, ::bandColor),
                    LinearGradient(0f, run.minOf { it.y }, 0f, plot.bottom, 0xFF000000.toInt(), 0, Shader.TileMode.CLAMP),
                    PorterDuff.Mode.DST_IN,
                )
                c.drawPath(area, paint)
                paint.shader = null
            }
            for ((top, bottom, color) in bands) {
                c.save()
                c.clipRect(0f, top, width.toFloat(), bottom)
                lineStroke.color = color
                if (run.size == 1) c.drawCircle(run[0].x, run[0].y, stroke * 0.8f, lineStroke) else c.drawPath(line, lineStroke)
                c.restore()
            }
        }

        // Latest reading: a dot with a halo in its range colour.
        if (last != null) {
            val cx = x(last.timeMillis)
            val cy = y(last.mgdl.toDouble())
            val color = colorFor(last.mgdl.toDouble(), state)
            paint.color = color and 0x00FFFFFF or 0x40000000
            c.drawCircle(cx, cy, stroke * 3.2f, paint)
            paint.color = 0xFF000000.toInt()
            c.drawCircle(cx, cy, stroke * 2.1f, paint)
            paint.color = color
            c.drawCircle(cx, cy, stroke * 1.5f, paint)
        }

        // Treatments. Labels that would overlap the previous one on the same row are skipped.
        val markLabel = Paint(label).apply { this.textSize = textSize * 0.95f; textAlign = Paint.Align.LEFT; typeface = Typeface.DEFAULT_BOLD }
        fun row(items: List<Treatment>, rowY: Float, color: Int, text: (Treatment) -> String?) {
            var free = Float.NEGATIVE_INFINITY
            items.sortedBy { text(it) != null }.forEach {
                val cx = x(it.timeMillis)
                val t = text(it)
                if (t == null) {
                    // Automatic boluses: a short faint tick, so they do not read as part of a label.
                    paint.color = color and 0x00FFFFFF or 0x99000000.toInt()
                    c.drawRoundRect(cx - stroke * 0.35f, rowY - dot, cx + stroke * 0.35f, rowY + dot, stroke, stroke, paint)
                    return@forEach
                }
                paint.color = color
                c.drawCircle(cx, rowY, dot, paint)
                val tx = cx + dot * 1.6f
                if (labels && tx >= free && tx + markLabel.measureText(t) <= width - inset) {
                    outlined(c, t, tx, rowY + textSize * 0.34f, markLabel, color)
                    free = tx + markLabel.measureText(t) + dot * 2
                }
            }
        }
        if (carbs.isNotEmpty()) row(carbs, carbRow / 2 + stroke, COLOR_CARBS) { "${formatAmount(it.carbs, 0)}g" }
        if (boluses.isNotEmpty()) row(boluses, plot.bottom + stroke + bolusRow / 2, COLOR_INSULIN) { if (it.automatic) null else "${formatAmount(it.insulin, 1)}U" }

        if (visible.isEmpty()) {
            label.textAlign = Paint.Align.CENTER
            c.drawText("No readings", plot.centerX(), plot.centerY() + textSize * 0.35f, label)
        }
        return bmp
    }

    /**
     * A horizontal gradient with hard stops where the polyline through [run] crosses [yHigh] or
     * [yLow], so every column gets the colour of the line above it. Alpha 25%.
     */
    private fun glowColors(run: List<PointF>, yHigh: Float, yLow: Float, bandColor: (Float) -> Int): Shader {
        val x0 = run.first().x
        val span = max(run.last().x - x0, 1f)
        val colors = mutableListOf<Int>()
        val stops = mutableListOf<Float>()
        fun stop(x: Float, color: Int) { colors += color and 0x00FFFFFF or 0x40000000; stops += ((x - x0) / span).coerceIn(0f, 1f) }
        stop(x0, bandColor(run.first().y))
        for (i in 0 until run.size - 1) {
            val a = run[i]
            val b = run[i + 1]
            // A segment can cross both guides (low to high in one step): handle them in x order.
            val towardsB = if (b.y > a.y) 0.5f else -0.5f
            listOf(yHigh, yLow)
                .filter { (a.y - it) * (b.y - it) < 0 }
                .map { it to a.x + (b.x - a.x) * (it - a.y) / (b.y - a.y) }
                .sortedBy { it.second }
                .forEach { (guide, xc) ->
                    stop(xc, colors.last())
                    stop(xc, bandColor(guide + towardsB))
                }
        }
        stop(run.last().x, bandColor(run.last().y))
        return LinearGradient(x0, 0f, x0 + span, 0f, colors.toIntArray(), stops.toFloatArray(), Shader.TileMode.CLAMP)
    }

    /** [text] with a black outline first, so lines and ticks never run into it. */
    private fun outlined(c: Canvas, text: String, x: Float, y: Float, paint: Paint, color: Int) {
        val style = paint.style
        val strokeWidth = paint.strokeWidth
        paint.style = Paint.Style.STROKE; paint.strokeWidth = paint.textSize * 0.3f; paint.color = 0xFF000000.toInt()
        c.drawText(text, x, y, paint)
        paint.style = Paint.Style.FILL; paint.color = color
        c.drawText(text, x, y, paint)
        paint.style = style; paint.strokeWidth = strokeWidth
    }

    /** Catmull-Rom spline through [points], as cubic Béziers. */
    private fun smooth(points: List<PointF>): Path = Path().apply {
        if (points.isEmpty()) return@apply
        moveTo(points[0].x, points[0].y)
        for (i in 0 until points.size - 1) {
            val p0 = points[max(i - 1, 0)]
            val p1 = points[i]
            val p2 = points[i + 1]
            val p3 = points[min(i + 2, points.size - 1)]
            cubicTo(
                p1.x + (p2.x - p0.x) / 6, p1.y + (p2.y - p0.y) / 6,
                p2.x - (p3.x - p1.x) / 6, p2.y - (p3.y - p1.y) / 6,
                p2.x, p2.y,
            )
        }
    }
}
