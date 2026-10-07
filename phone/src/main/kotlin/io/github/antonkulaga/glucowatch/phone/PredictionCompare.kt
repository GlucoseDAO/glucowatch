package io.github.antonkulaga.glucowatch.phone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import glucowatch.core.GlucoseReading
import glucowatch.core.PredictionCheck

/** One stored forecast (dashed) laid over the readings that landed in its window (solid). */
fun predictionComparison(check: PredictionCheck, readings: List<GlucoseReading>, width: Int, height: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(0xFF000000.toInt())
    val points = check.prediction.points.sortedBy { it.timeMillis }
    if (points.isEmpty()) return bitmap
    val from = check.savedAt
    val to = points.last().timeMillis
    val actual = readings.filter { it.timeMillis in from..to }.sortedBy { it.timeMillis }
    val values = actual.map { it.mgdl.toDouble() } + points.map { it.mgdl }
    var yMin = (values.minOrNull() ?: 70.0) - 12.0
    var yMax = (values.maxOrNull() ?: 180.0) + 12.0
    if (yMax - yMin < 30.0) {
        yMin -= 15.0
        yMax += 15.0
    }
    val plot = RectF(8f, 12f, width - 8f, height - 12f)
    val span = (to - from).coerceAtLeast(1L)
    fun x(t: Long) = plot.left + plot.width() * (t - from).toFloat() / span
    fun y(v: Double) = plot.bottom - plot.height() * ((v - yMin) / (yMax - yMin)).toFloat()
    if (actual.size >= 2) {
        val path = Path()
        actual.forEachIndexed { index, reading ->
            val px = x(reading.timeMillis)
            val py = y(reading.mgdl.toDouble())
            if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = 0xFFFFFFFF.toInt()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        })
    }
    val forecast = Path()
    points.forEachIndexed { index, point ->
        val px = x(point.timeMillis)
        val py = y(point.mgdl)
        if (index == 0) forecast.moveTo(px, py) else forecast.lineTo(px, py)
    }
    canvas.drawPath(forecast, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = 0xFFA78BDB.toInt()
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
        strokeCap = Paint.Cap.ROUND
    })
    return bitmap
}
