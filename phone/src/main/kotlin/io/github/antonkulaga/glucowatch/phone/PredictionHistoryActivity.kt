package io.github.antonkulaga.glucowatch.phone

import android.app.Activity
import android.os.Bundle
import android.util.TypedValue
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import glucowatch.core.PredictionChecks
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
            setPadding(dp(16), dp(16), dp(16), dp(32))
            setBackgroundColor(Brand.BACKGROUND)
        }
        val checks = PredictionChecks.load(File(filesDir, PredictionChecks.FILE_NAME)).asReversed()
        val readings = PhoneRepository(this).state().readings
        column.addView(caption("White is what happened. Purple dashes are the forecast stored at that time.", small = true))
        if (checks.isEmpty()) {
            column.addView(caption("No stored forecasts yet. In the menu, Check prediction is under Refresh now."))
        }
        val stamp = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault())
        val width = resources.displayMetrics.widthPixels - dp(32)
        checks.forEach { check ->
            val end = check.prediction.points.maxOf { it.timeMillis }
            val minutes = ((end - check.savedAt) / 60_000L).coerceAtLeast(0)
            column.addView(caption("${stamp.format(Instant.ofEpochMilli(check.savedAt))} · ${minutes} min"))
            val arrived = readings.any { it.timeMillis in check.savedAt..end }
            if (!arrived) column.addView(caption("Waiting for readings in this window.", small = true))
            column.addView(ImageView(this).apply {
                setImageBitmap(predictionComparison(check, readings, width, dp(220)))
                setPadding(0, dp(8), 0, dp(20))
            })
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Brand.BACKGROUND)
            addView(column)
        })
    }

    private fun caption(text: String, small: Boolean = false) = TextView(this).apply {
        this.text = text
        setTextColor(if (small) Brand.MUTED else Brand.TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) 14f else 16f)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
