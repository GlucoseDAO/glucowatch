package io.github.antonkulaga.glucowatch.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import glucowatch.core.LoopStatus
import glucowatch.core.Region
import glucowatch.core.formatAge
import glucowatch.core.formatAmount
import glucowatch.core.lastCarbs
import glucowatch.core.lastDelta
import glucowatch.core.lastManualBolus
import io.github.antonkulaga.glucowatch.chart.ChartRenderer
import io.github.antonkulaga.glucowatch.data.DataSource
import io.github.antonkulaga.glucowatch.data.GlucoseRepository
import io.github.antonkulaga.glucowatch.data.GlucoseState
import io.github.antonkulaga.glucowatch.data.RefreshReceiver
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Current value, full chart and status; tapping any complication opens this screen. */
class MainActivity : Activity() {
    private val scope = MainScope()
    private lateinit var value: TextView
    private lateinit var status: TextView
    private lateinit var chips: LinearLayout
    private lateinit var iob: TextView
    private lateinit var cob: TextView
    private lateinit var chart: ImageView
    private lateinit var forecast: TextView
    private lateinit var therapy: TextView
    private lateinit var source: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen = resources.displayMetrics.widthPixels
        // Round screens clip the corners: keep text inside the circle while it scrolls past. The
        // first screen matches the tile: value and change on top, the chart through the middle.
        val side = (screen * 0.09).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(side, (screen * 0.08).toInt(), side, (screen * 0.3).toInt())
            clipToPadding = false  // the chart reaches into the side padding
        }
        value = TextView(this).apply {
            textSize = 38f; gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
        }
        status = text(13f, 0xFFB0B8C4.toInt())
        iob = chip(ChartRenderer.COLOR_INSULIN)
        cob = chip(ChartRenderer.COLOR_CARBS)
        chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(iob); addView(cob, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        }
        chart = ImageView(this).apply { adjustViewBounds = true }
        forecast = text(13f, ChartRenderer.COLOR_FORECAST)
        therapy = text(13f, 0xFFE5E7EB.toInt()).apply { setLineSpacing(dp(3).toFloat(), 1f) }
        source = text(11f, 0xFF8B95A3.toInt())
        val refresh = Button(this).apply {
            text = "Refresh"; setOnClickListener { refresh() }
            Brand.style(this, primary = true)
        }
        val settings = Button(this).apply {
            text = "Settings"
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
            Brand.style(this, primary = false)
        }
        column.addView(value, wrap(0))
        column.addView(status, wrap(2))
        // Rim to rim, like the face: the chart ignores the column's side padding.
        column.addView(chart, wrap(6).apply { marginStart = -side; marginEnd = -side })
        column.addView(forecast, wrap(4))
        column.addView(chips, wrap(8))
        column.addView(therapy, wrap(8))
        column.addView(source, wrap(8))
        column.addView(refresh, wrap(14))
        column.addView(settings, wrap(6))
        setContentView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(column) })
    }

    override fun onResume() {
        super.onResume()
        show(GlucoseRepository(this).state())
        refresh()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refresh() {
        source.text = "Refreshing…"
        scope.launch { show(RefreshReceiver.refreshNow(applicationContext)) }
    }

    private fun show(state: GlucoseState) {
        val unit = state.settings.unit
        val latest = state.latest
        val now = System.currentTimeMillis()
        if (latest == null) {
            value.text = "---"
            value.setTextColor(Color.WHITE)
        } else {
            value.text = "${unit.format(latest.mgdl.toDouble())} ${latest.trend.arrow}"
            value.setTextColor(if (state.isStale()) Color.GRAY else ChartRenderer.colorFor(latest.mgdl.toDouble(), state))
        }
        status.text = listOfNotNull(
            state.readings.lastDelta()?.let { "${unit.formatDelta(it)} ${unit.label}" },
            state.ageMinutes()?.let { if (it < 1) "now" else "${formatAge(it)} ago" },
        ).joinToString("  ·  ")

        val loop = state.loop
        val fresh = state.freshLoop(now)
        iob.text = fresh?.iob?.let { "${formatAmount(it, 1)} U  IOB" }.orEmpty()
        cob.text = fresh?.cob?.let { "${formatAmount(it, 0)} g  COB" }.orEmpty()
        iob.visibility = if (iob.text.isEmpty()) View.GONE else View.VISIBLE
        cob.visibility = if (cob.text.isEmpty()) View.GONE else View.VISIBLE
        chips.visibility = if (fresh == null) View.GONE else View.VISIBLE

        val width = resources.displayMetrics.widthPixels
        chart.setImageBitmap(ChartRenderer.render(state, width, (width * 0.38).toInt(), edge = true))

        val p = state.prediction?.points?.lastOrNull()
        val fromLoop = state.settings.predictorId == LoopStatus.MODEL_ID && state.settings.source == DataSource.NIGHTSCOUT
        val minutes = state.settings.horizonMinutes
        forecast.text = when {
            !state.settings.predictionEnabled -> ""
            p == null && fromLoop -> "No fresh loop forecast"
            p == null -> ""
            else -> "${unit.format(p.mgdl)} in $minutes min" + if (fromLoop) "  ·  ${loop?.forecastName ?: "loop"}" else ""
        }
        forecast.visibility = if (forecast.text.isEmpty()) View.GONE else View.VISIBLE

        val bolus = state.treatments.lastManualBolus(now)
        val carbs = state.treatments.lastCarbs(now)
        therapy.text = listOfNotNull(
            loop?.takeIf { it.isStale(now) }?.let { "Loop quiet for ${formatAge(it.ageMinutes(now))}" },
            bolus?.let { "Bolus ${formatAmount(it.insulin)} U  ·  ${formatAge((now - it.timeMillis) / 60_000)} ago" },
            carbs?.let { "Carbs ${formatAmount(it.carbs, 0)} g  ·  ${formatAge((now - it.timeMillis) / 60_000)} ago" },
        ).joinToString("\n")
        therapy.visibility = if (therapy.text.isEmpty()) View.GONE else View.VISIBLE

        val region = if (state.settings.region == Region.OUS) "EU" else state.settings.region.name
        source.text = listOfNotNull(
            when (state.settings.source) {
                DataSource.DEMO -> "Demo data"
                DataSource.SHARE -> "Dexcom Share · $region"
                DataSource.NIGHTSCOUT -> "Nightscout · API ${state.settings.nightscoutApi.name.lowercase()}"
            },
            state.lastError?.let { "⚠ $it" },
        ).joinToString("\n")
        source.setTextColor(if (state.lastError != null) ChartRenderer.COLOR_HIGH else 0xFF8B95A3.toInt())
    }

    private fun text(size: Float, color: Int) = TextView(this).apply {
        textSize = size; gravity = Gravity.CENTER; setTextColor(color)
    }

    /** A rounded pill with a dark fill and the value in [color]. */
    private fun chip(color: Int) = TextView(this).apply {
        textSize = 13f; setTextColor(color); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0xFF1C2230.toInt()) }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun wrap(top: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(top) }
}
