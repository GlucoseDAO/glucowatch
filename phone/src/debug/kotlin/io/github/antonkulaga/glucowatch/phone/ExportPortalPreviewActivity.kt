package io.github.antonkulaga.glucowatch.phone

import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.time.LocalDate

/** Local synthetic CSVs exercise real blob capture and the two-source wizard without a login or network. */
class ExportPortalPreviewActivity : ExportPortalActivity() {
    override fun preferExternalBrowser() = intent.getBooleanExtra("externalFixture", false) || super.preferExternalBrowser()
    override fun externalAddress(address: String): String = intent.getStringExtra("fixtureBase")?.let { base ->
        "$base/${if (intent.getStringExtra("portal") == "CARELINK") "pump" else "sensor"}?start=${intent.getStringExtra("start")}&end=${intent.getStringExtra("end")}"
    } ?: address
    private fun syntheticCsv(): String {
        val carelink = intent.getStringExtra("portal") == "CARELINK"
        val start = LocalDate.parse(intent.getStringExtra("start"))
        val end = LocalDate.parse(intent.getStringExtra("end"))
        return buildString {
            append(if (carelink) "Date,Time,Bolus Volume Delivered (U),BWZ Carb Input (grams)\n"
                else "Timestamp (YYYY-MM-DDThh:mm:ss),Event Type,Glucose Value (mg/dL)\n")
            var day = start
            while (!day.isAfter(end)) {
                append(if (carelink) "${day.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu"))},12:00:00,1.5,20\n"
                    else "${day}T12:00:00,Glucose,125\n")
                day = day.plusDays(1)
            }
        }
    }

    override fun interceptPortalRequest(request: WebResourceRequest): WebResourceResponse? {
        if (request.url.path != "/api/subject/fixture/export") return null
        val mode = intent.getStringExtra("mode") ?: "blob"
        val allowed = request.method == "POST" && (mode == "form" || request.requestHeaders["Authorization"] == "Bearer synthetic-only")
        return WebResourceResponse(if (allowed) "text/csv" else "text/html", "utf-8",
            if (allowed) 200 else 401, if (allowed) "OK" else "Unauthorized",
            mapOf("Content-Disposition" to "attachment; filename=synthetic.csv"),
            (if (allowed) syntheticCsv() else "<html>Sign in</html>").byteInputStream())
    }

    override fun loadPortal(view: WebView, address: String) {
        val start = LocalDate.parse(intent.getStringExtra("start"))
        val end = LocalDate.parse(intent.getStringExtra("end"))
        val carelink = intent.getStringExtra("portal") == "CARELINK"
        val popup = intent.getBooleanExtra("popup", false)
        val mode = intent.getStringExtra("mode") ?: if (popup) "popup" else "blob"
        val csv = syntheticCsv()
        val html = """
            <!doctype html><html><meta name="viewport" content="width=device-width,initial-scale=1">
            <body style="font:18px sans-serif;padding:24px;${if (intent.getBooleanExtra("wide", false)) "width:1200px" else ""}"><h2>${if (carelink) "CareLink" else "Clarity"} download fixture</h2>
            <p>Synthetic data only. $start to $end. No network or login.</p>
            <button style="padding:20px" onclick="downloadCsv()">Download CSV</button>
            <script>
              function downloadCsv() {
                const mode=${org.json.JSONObject.quote(mode)}, csv=${org.json.JSONObject.quote(csv)};
                if (mode==='fetch') {
                  fetch('/api/subject/fixture/export',{method:'POST',headers:{Authorization:'Bearer synthetic-only'},body:'dates=fixture'}).then(r=>r.text());
                  return;
                }
                if (mode==='xhr') {
                  const xhr=new XMLHttpRequest(); xhr.open('POST','/api/subject/fixture/export');
                  xhr.setRequestHeader('Authorization','Bearer synthetic-only'); xhr.send('dates=fixture'); return;
                }
                if (mode==='form') {
                  const form=document.createElement('form'); form.method='POST'; form.action='/api/subject/fixture/export';
                  const field=document.createElement('input'); field.type='hidden'; field.name='accessToken'; field.value='synthetic-only';
                  form.append(field); document.body.append(form); form.submit(); return;
                }
                let target=window;
                if (mode==='popup') target=window.open('about:blank','_blank');
                if (mode==='iframe') {
                  const frame=document.createElement('iframe'); frame.id='export-frame'; document.body.append(frame); target=frame.contentWindow;
                }
                if (!target) { document.body.append('Download window was blocked'); return; }
                function collect() {
                  const a = target.document.createElement('a');
                  a.href = target.URL.createObjectURL(new target.Blob([csv], {type:'text/csv'}));
                  a.download = 'synthetic.csv'; target.document.body.appendChild(a); a.click();
                  target.URL.revokeObjectURL(a.href); a.remove();
                  if (mode==='iframe') document.getElementById('export-frame').remove();
                  if (mode==='popup') target.close();
                }
                if (target === window) collect(); else setTimeout(collect, 500);
              }
            </script></body></html>
        """.trimIndent()
        view.loadDataWithBaseURL(address, html, "text/html", "utf-8", address)
    }
}
