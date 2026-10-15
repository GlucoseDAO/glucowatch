package io.github.antonkulaga.glucowatch.phone

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import glucowatch.core.*

/** Local synthetic smoke-test fixture: no account, network, or health data. Debug APKs only. */
class ReportPreviewActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val label = TextView(this).apply { text = "Rendering synthetic report previews…" }
        setContentView(label)
        Thread {
            try {
                val chosen = intent.getStringExtra("content")?.let { ExportContent.valueOf(it) }
                val cases = if (intent.getBooleanExtra("all_cases", false)) ReportPreviewData.cases else
                    (chosen?.let { listOf(it) } ?: ExportContent.entries).map { intent.getIntExtra("days", 90) to it }
                cases.forEach { (days, content) ->
                    val report = ReportPreviewData.build(days, content)
                    cacheDir.resolve("standard-${days}-${content.name.lowercase()}.pdf").writeBytes(ReportPdf.render(report))
                }
                runOnUiThread { label.text = "Synthetic reports ready in private cache." }
            } catch (e: Exception) {
                cacheDir.resolve("report-preview-error.txt").writeText(e.stackTraceToString())
                runOnUiThread { label.text = "Report preview failed: ${e.javaClass.simpleName}: ${e.message}" }
            }
        }.start()
    }
}
