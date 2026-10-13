package io.github.antonkulaga.glucowatch.phone

import android.webkit.WebView
import java.time.LocalDate

/** Local synthetic CSVs exercise real blob capture and the two-source wizard without a login or network. */
class ExportPortalPreviewActivity : ExportPortalActivity() {
    override fun loadPortal(view: WebView, address: String) {
        val start = LocalDate.parse(intent.getStringExtra("start"))
        val end = LocalDate.parse(intent.getStringExtra("end"))
        val carelink = intent.getStringExtra("portal") == "CARELINK"
        val csv = buildString {
            append(if (carelink) "Date,Time,Bolus Volume Delivered (U),BWZ Carb Input (grams)\n"
                else "Timestamp (YYYY-MM-DDThh:mm:ss),Event Type,Glucose Value (mg/dL)\n")
            var day = start
            while (!day.isAfter(end)) {
                append(if (carelink) "${day.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu"))},12:00:00,1.5,20\n"
                    else "${day}T12:00:00,Glucose,125\n")
                day = day.plusDays(1)
            }
        }
        val html = """
            <!doctype html><html><meta name="viewport" content="width=device-width,initial-scale=1">
            <body style="font:18px sans-serif;padding:24px"><h2>${if (carelink) "CareLink" else "Clarity"} download fixture</h2>
            <p>Synthetic data only. $start to $end. No network or login.</p>
            <button style="padding:20px" onclick="downloadCsv()">Download CSV</button>
            <script>
              function downloadCsv() {
                const a = document.createElement('a');
                a.href = URL.createObjectURL(new Blob([${org.json.JSONObject.quote(csv)}], {type:'text/csv'}));
                a.download = 'synthetic.csv'; document.body.appendChild(a); a.click(); a.remove();
              }
            </script></body></html>
        """.trimIndent()
        view.loadDataWithBaseURL(address, html, "text/html", "utf-8", address)
    }
}
