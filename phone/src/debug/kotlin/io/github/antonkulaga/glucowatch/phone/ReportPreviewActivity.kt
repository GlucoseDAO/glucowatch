package io.github.antonkulaga.glucowatch.phone

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import glucowatch.core.*
import java.time.LocalDate
import java.time.ZoneOffset

/** Local synthetic smoke-test fixture: no account, network, or health data. Debug APKs only. */
class ReportPreviewActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val label = TextView(this).apply { text = "Rendering synthetic report previews…" }
        setContentView(label)
        Thread {
            try {
                val today = LocalDate.of(2026, 10, 13)
                val now = today.atTime(23, 59).toInstant(ZoneOffset.UTC).toEpochMilli()
                val readings = DemoData.readings(now, 90 * 24)
                val pump = DemoData.treatments(now, 90 * 24)
                val rows = ExportRow.fromCareLinkCache(readings, pump, null) + listOf(
                    ExportRow(now - 60_000, ExportRow.SOURCE_CARELINK, ExportRow.KIND_ALARM, markerType = "LOW_GLUCOSE"),
                    ExportRow(now - 120_000, ExportRow.SOURCE_CARELINK, ExportRow.KIND_CALIBRATION, value = 120.0, valueUnit = "mg/dL"),
                    ExportRow(now - 180_000, ExportRow.SOURCE_CARELINK, ExportRow.KIND_DEVICE, detail = "{\"deviceId\":\"" + "demo".repeat(60) + "\"}"))
                val chosen = intent.getStringExtra("content")?.let { ExportContent.valueOf(it) }
                (chosen?.let { listOf(it) } ?: ExportContent.entries).forEach { content ->
                    val window = (ExportWindow.resolve(LatestDevice.MINIMED_PUMP, today.minusDays(100), today, 90, today, content) as ExportRequest.Ready).window
                    val report = GlucoseReport.build(if (content.sensor) readings else emptyList(), if (content.pump) pump else emptyList(),
                        "Synthetic demo", ZoneOffset.UTC, now, window, rows.filter(content::includes))
                    cacheDir.resolve("preview-${content.name.lowercase()}.pdf").writeBytes(ReportPdf.render(report, now))
                }
                runOnUiThread { label.text = "Synthetic reports ready in private cache." }
            } catch (e: Exception) { runOnUiThread { label.text = "Report preview failed: ${e.javaClass.simpleName}: ${e.message}" } }
        }.start()
    }
}
