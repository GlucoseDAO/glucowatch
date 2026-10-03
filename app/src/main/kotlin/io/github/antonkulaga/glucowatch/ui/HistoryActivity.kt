package io.github.antonkulaga.glucowatch.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import glucowatch.core.SourceSync
import io.github.antonkulaga.glucowatch.chart.ChartRenderer
import io.github.antonkulaga.glucowatch.data.GlucoseRepository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The day the watch has stored, drawn as one chart wider than the screen.
 * The bezel and a finger drag move along it a short way at a time.
 */
class HistoryActivity : Activity() {
    private lateinit var caption: TextView
    private lateinit var scroller: HorizontalScrollView
    private lateinit var chart: ImageView
    private var rangeStart = 0L
    private var rangeEnd = 0L
    private var stripWidth = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen = resources.displayMetrics.widthPixels
        val side = (screen * 0.08).toInt()
        caption = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Brand.TEXT)
        }
        chart = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setBackgroundColor(Color.BLACK)
        }
        scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(Color.BLACK)
            addView(chart)
            attachRotaryHorizontal()
            setOnScrollChangeListener { _, x, _, _, _ -> updateCaption(x) }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.BLACK)
            setPadding(0, (screen * 0.12).toInt(), 0, side)
            addView(caption, LinearLayout.LayoutParams(-1, -2).apply { marginStart = side; marginEnd = side })
            addView(scroller, LinearLayout.LayoutParams(-1, (screen * 0.62).toInt()).apply { topMargin = side / 2 })
        }
        setContentView(column)
    }

    override fun onResume() {
        super.onResume()
        show()
        scroller.requestFocus()
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean =
        if (scroller.onRotaryScrollHorizontal(event)) true else super.onGenericMotionEvent(event)

    private fun show() {
        val state = GlucoseRepository(this).state()
        val end = System.currentTimeMillis()
        val start = end - SourceSync.DAY_MS
        rangeStart = start
        rangeEnd = end
        val screen = resources.displayMetrics.widthPixels
        val height = (screen * 0.62).toInt()
        val sample = Paint().apply { textSize = height * 0.075f }
        // Wide enough that every hour keeps its own label, so the day is longer than one screen.
        val strip = (sample.measureText("00:00") * 1.45f * 24f).toInt().coerceAtLeast(screen)
        stripWidth = strip
        val zone = ZoneId.systemDefault()
        val bitmap = ChartRenderer.renderHistory(state, strip, height, start, end, zone)
        chart.setImageBitmap(bitmap)
        chart.layoutParams = FrameLayout.LayoutParams(bitmap.width, bitmap.height)
        scroller.post {
            scroller.scrollTo(bitmap.width, 0)
            updateCaption(scroller.scrollX)
        }
    }

    private fun updateCaption(scrollX: Int) {
        val view = scroller.width.coerceAtLeast(1)
        val span = (rangeEnd - rangeStart).coerceAtLeast(1L)
        val maxScroll = (stripWidth - view).coerceAtLeast(0)
        val left = scrollX.coerceIn(0, maxScroll)
        val start = rangeStart + span * left / stripWidth
        val end = (rangeStart + span * (left + view) / stripWidth).coerceAtMost(rangeEnd)
        val zone = ZoneId.systemDefault()
        caption.text = windowLabel(start, end, zone)
        chart.contentDescription = caption.text
    }

    private fun windowLabel(start: Long, end: Long, zone: ZoneId): String {
        val day = DateTimeFormatter.ofPattern("dd.MM")
        val time = DateTimeFormatter.ofPattern("HH:mm")
        val a = Instant.ofEpochMilli(start).atZone(zone)
        val b = Instant.ofEpochMilli(end).atZone(zone)
        return if (a.toLocalDate() == b.toLocalDate()) "${a.format(day)}  ${a.format(time)}–${b.format(time)}"
        else "${a.format(day)} ${a.format(time)} – ${b.format(day)} ${b.format(time)}"
    }
}
