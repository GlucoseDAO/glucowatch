package io.github.antonkulaga.glucowatch.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import glucowatch.core.Region
import glucowatch.core.lastDelta
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
    private lateinit var chart: ImageView
    private lateinit var forecast: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(18)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, dp(28), pad, dp(40))
        }
        value = TextView(this).apply { textSize = 40f; gravity = Gravity.CENTER }
        status = TextView(this).apply { textSize = 12f; gravity = Gravity.CENTER; setTextColor(Color.LTGRAY) }
        chart = ImageView(this).apply { adjustViewBounds = true }
        forecast = TextView(this).apply { textSize = 13f; gravity = Gravity.CENTER; setTextColor(0xFFB388FF.toInt()) }
        val refresh = Button(this).apply { text = "Refresh"; setOnClickListener { refresh() } }
        val settings = Button(this).apply {
            text = "Settings"
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        listOf(value, status, chart, forecast, refresh, settings).forEach { column.addView(it, wrap()) }
        setContentView(ScrollView(this).apply { addView(column) })
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
        status.text = "Refreshing…"
        scope.launch { show(RefreshReceiver.refreshNow(applicationContext)) }
    }

    private fun show(state: GlucoseState) {
        val unit = state.settings.unit
        val latest = state.latest
        if (latest == null) {
            value.text = "---"
            value.setTextColor(Color.WHITE)
        } else {
            value.text = "${unit.format(latest.mgdl.toDouble())} ${latest.trend.arrow}"
            value.setTextColor(if (state.isStale()) Color.GRAY else ChartRenderer.colorFor(latest.mgdl.toDouble(), state))
        }
        val region = if (state.settings.region == Region.OUS) "EU" else state.settings.region.name
        val source = if (state.settings.source == DataSource.DEMO) "Demo data" else "Share · $region"
        val parts = listOfNotNull(
            state.ageMinutes()?.let { "${it} min ago" },
            state.readings.lastDelta()?.let { "${unit.formatDelta(it)} ${unit.label}" },
            source,
            state.lastError?.let { "⚠ $it" },
        )
        status.text = parts.joinToString("\n")

        val width = (resources.displayMetrics.widthPixels * 0.85).toInt()
        chart.setImageBitmap(ChartRenderer.render(state, width, (width * 0.5).toInt()))

        val p = state.prediction?.points?.lastOrNull()
        forecast.text = if (p == null) "" else "Forecast in ${state.settings.horizonMinutes}m: ${unit.format(p.mgdl)}"
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun wrap() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(4) }
}
