package io.github.antonkulaga.glucowatch.ui

import android.app.Activity
import android.os.Bundle
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ViewGroup
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
import io.github.antonkulaga.glucowatch.chart.ChartRenderer
import io.github.antonkulaga.glucowatch.data.GlucoseRepository
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/** One stored forecast, with the bezel or a tap choosing the time to compare. */
class PredictionDetailActivity : Activity() {
    private lateinit var check: PredictionCheck
    private lateinit var review: ForecastReview
    private lateinit var readings: List<GlucoseReading>
    private lateinit var unit: GlucoseUnit
    private lateinit var chart: ImageView
    private lateinit var detail: TextView
    private var selected = 0
    private var width = 0
    private val stamp = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val checks = PredictionChecks.load(File(filesDir, PredictionChecks.FILE_NAME)).asReversed()
        val item = checks.getOrNull(intent.getIntExtra(EXTRA_INDEX, 0)) ?: return finish()
        check = item
        val state = GlucoseRepository(this).state()
        readings = state.readings
        unit = state.settings.unit
        review = ForecastReviewer.review(check, readings, System.currentTimeMillis())
        val now = System.currentTimeMillis()
        selected = review.samples.indexOfLast { it.timeMillis <= now }.coerceAtLeast(0)
        width = resources.displayMetrics.widthPixels - dp(16)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(28))
        }
        column.addView(caption(ForecastReviewer.title(review.modelId)))
        column.addView(caption(review.status, small = true, warn = review.status.startsWith("Interrupted")))
        column.addView(caption("White is the reading. Purple dashes are the forecast. Turn the bezel to move.", small = true))
        chart = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(width, dp(150))
            scaleType = ImageView.ScaleType.FIT_XY
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_MOVE) selectAt(event.x)
                true
            }
        }
        column.addView(chart)
        detail = caption("")
        column.addView(detail)
        val scroll = ScrollView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
            setOnGenericMotionListener { _, event ->
                val steps = event.rotarySteps() ?: return@setOnGenericMotionListener false
                if (review.samples.isEmpty()) return@setOnGenericMotionListener true
                selected = (selected + steps.roundToInt()).coerceIn(0, review.samples.lastIndex)
                show()
                true
            }
            addView(column)
        }
        setContentView(scroll)
        scroll.requestFocus()
        show()
    }

    private fun selectAt(x: Float) {
        val samples = review.samples
        if (samples.isEmpty()) return
        val at = timeAt(x)
        val index = samples.indices.minBy { abs(samples[it].timeMillis - at) }
        if (index == selected) return
        selected = index
        show()
    }

    private fun timeAt(x: Float): Long {
        val from = check.savedAt
        val to = review.samples.lastOrNull()?.timeMillis ?: from
        val fraction = ((x - 6f) / (width - 12f)).coerceIn(0f, 1f)
        return from + (fraction * (to - from).coerceAtLeast(1L)).toLong()
    }

    private fun show() {
        val sample = review.samples.getOrNull(selected) ?: return
        chart.setImageBitmap(ChartRenderer.comparison(check, readings, width, dp(150), sample.timeMillis))
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
            warn -> 0xFFFFB020.toInt()
            small -> 0xFF9AA3A8.toInt()
            else -> 0xFFFFFFFF.toInt()
        })
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) 12f else 15f)
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_INDEX = "index"
    }
}
