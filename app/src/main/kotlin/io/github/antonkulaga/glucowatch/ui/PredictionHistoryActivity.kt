package io.github.antonkulaga.glucowatch.ui

import android.app.Activity
import android.os.Bundle
import android.util.TypedValue
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import glucowatch.core.PredictionChecks
import io.github.antonkulaga.glucowatch.chart.ChartRenderer
import io.github.antonkulaga.glucowatch.data.GlucoseRepository
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Each stored forecast drawn over the readings that arrived in its window. */
class PredictionHistoryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(28))
        }
        val checks = PredictionChecks.load(File(filesDir, PredictionChecks.FILE_NAME)).asReversed()
        val readings = GlucoseRepository(this).state().readings
        column.addView(caption("White is what happened. Purple dashes are the forecast stored at that time.", small = true))
        if (checks.isEmpty()) {
            column.addView(caption("No stored forecasts yet. On the main screen, Check prediction is under Refresh."))
        }
        val stamp = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault())
        val width = resources.displayMetrics.widthPixels - dp(16)
        checks.forEach { check ->
            val end = check.prediction.points.maxOf { it.timeMillis }
            val minutes = ((end - check.savedAt) / 60_000L).coerceAtLeast(0)
            column.addView(caption("${stamp.format(Instant.ofEpochMilli(check.savedAt))} · ${minutes} min"))
            val arrived = readings.any { it.timeMillis in check.savedAt..end }
            if (!arrived) column.addView(caption("Waiting for readings in this window.", small = true))
            column.addView(ImageView(this).apply {
                setImageBitmap(ChartRenderer.comparison(check, readings, width, dp(110)))
                setPadding(0, dp(4), 0, dp(12))
            })
        }
        setContentView(ScrollView(this).apply { addView(column); attachRotary() })
    }

    private fun caption(text: String, small: Boolean = false) = TextView(this).apply {
        this.text = text
        setTextColor(if (small) 0xFF9AA3A8.toInt() else 0xFFFFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) 12f else 14f)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
