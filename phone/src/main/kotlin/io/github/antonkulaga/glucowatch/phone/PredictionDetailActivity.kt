package io.github.antonkulaga.glucowatch.phone

import android.app.Activity
import android.os.Bundle
import android.util.TypedValue
import android.view.MotionEvent
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import glucowatch.core.ForecastReview
import glucowatch.core.ForecastReviewer
import glucowatch.core.GlucoseReading
import glucowatch.core.GlucoseUnit
import glucowatch.core.PredictionCheck
import glucowatch.core.PredictionChecks
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** One stored forecast. A tap or a drag along the chart chooses the time to compare. */
class PredictionDetailActivity : Activity() {
    private lateinit var check: PredictionCheck
    private lateinit var review: ForecastReview
    private lateinit var readings: List<GlucoseReading>
    private lateinit var unit: GlucoseUnit
    private lateinit var chart: ImageView
    private lateinit var detail: TextView
    private var selected = 0
    private var width = 0
    private val stamp = DateTimeFormatter.ofPattern("EEE d MMM  HH:mm").withZone(ZoneId.systemDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val checks = PredictionChecks.load(File(filesDir, PredictionChecks.FILE_NAME)).asReversed()
        val item = checks.getOrNull(intent.getIntExtra(EXTRA_INDEX, 0)) ?: return finish()
        check = item
        val state = PhoneRepository(this).state()
        readings = state.readings
        unit = state.settings.unit
        review = ForecastReviewer.review(check, readings, System.currentTimeMillis())
        val now = System.currentTimeMillis()
        selected = review.samples.indexOfLast { it.timeMillis <= now }.coerceAtLeast(0)
        width = resources.displayMetrics.widthPixels - dp(32)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
            setBackgroundColor(Brand.BACKGROUND)
        }
        column.addView(caption(ForecastReviewer.title(review.modelId)))
        column.addView(caption(review.status, small = true, warn = review.status.startsWith("Interrupted")))
        column.addView(caption("White is the reading. Purple dashes are the forecast. Drag along the chart to move.", small = true))
        chart = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(width, dp(280))
            scaleType = ImageView.ScaleType.FIT_XY
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE ||
                    event.action == MotionEvent.ACTION_UP
                ) selectAt(event.x)
                true
            }
        }
        column.addView(chart)
        detail = caption("")
        column.addView(detail)
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Brand.BACKGROUND)
            addView(column)
        })
        show()
    }

    private fun selectAt(x: Float) {
        val samples = review.samples
        if (samples.isEmpty()) return
        val from = check.savedAt
        val to = samples.last().timeMillis
        val fraction = ((x - 8f) / (width - 16f)).coerceIn(0f, 1f)
        val at = from + (fraction * (to - from).coerceAtLeast(1L)).toLong()
        val index = samples.indices.minBy { abs(samples[it].timeMillis - at) }
        if (index == selected) return
        selected = index
        show()
    }

    private fun show() {
        val sample = review.samples.getOrNull(selected) ?: return
        chart.setImageBitmap(predictionComparison(check, readings, width, dp(280), sample.timeMillis))
        val lines = mutableListOf(
            stamp.format(Instant.ofEpochMilli(sample.timeMillis)),
            "Predicted  ${unit.format(sample.predictedMgdl)} ${unit.label}",
        )
        val actual = sample.actualMgdl
        if (actual == null) {
            lines += "Actual  —"
            lines += "Difference  —"
        } else {
            lines += "Actual  ${unit.format(actual.toDouble())} ${unit.label}"
            lines += "Difference  ${unit.formatDelta(sample.predictedMgdl - actual)} ${unit.label}"
        }
        detail.text = lines.joinToString("\n")
    }

    private fun caption(text: String, small: Boolean = false, warn: Boolean = false) = TextView(this).apply {
        this.text = text
        setTextColor(when {
            warn -> Brand.ORANGE
            small -> Brand.MUTED
            else -> Brand.TEXT
        })
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) 14f else 18f)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_INDEX = "index"
    }
}
