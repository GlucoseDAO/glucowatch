package io.github.antonkulaga.glucowatch.phone

import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebMessageCompat
import glucowatch.core.ExportPortal
import glucowatch.core.HistoricalCsv
import glucowatch.core.Region
import org.json.JSONObject

/** CSV-only, origin-scoped browser messages. Request authentication stays inside WebView. */
class PortalCsvCapture(private val portal: ExportPortal, private val country: String, private val region: Region) {
    private data class Installed(val name: String, val script: ScriptHandler)
    private val installed = mutableMapOf<WebView, Installed>()

    fun install(view: WebView, receive: (WebView, String, String, JavaScriptReplyProxy) -> Unit): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return false
        val origins = portal.captureOrigins(country, region)
        val name = "glucoCsv" + java.util.UUID.randomUUID().toString().replace("-", "")
        WebViewCompat.addWebMessageListener(view, name, origins) { source, message, origin, _, reply ->
            if (portal.permits(origin.toString(), country, region, download = true)) {
                val data = if (message.type == WebMessageCompat.TYPE_STRING) message.data else null
                if (data != null && data.length <= 66_000) receive(source, origin.toString(), data, reply)
                else runCatching { reply.postMessage("reject") }
            }
        }
        val script = view.context.assets.open("portal-csv-capture.js").bufferedReader().use { it.readText() }
            .replace("__BRIDGE_NAME__", JSONObject.quote(name))
            .replace("__MAX_BYTES__", HistoricalCsv.MAX_BYTES.toString())
        installed[view] = Installed(name, WebViewCompat.addDocumentStartJavaScript(view, script, origins))
        return true
    }

    fun request(view: WebView, address: String): Boolean {
        if (view !in installed) return false
        view.evaluateJavascript("window.__glucoCsvCapture && window.__glucoCsvCapture.capture(${JSONObject.quote(address)});", null)
        return true
    }

    fun remove(view: WebView) {
        installed.remove(view)?.let { it.script.remove(); WebViewCompat.removeWebMessageListener(view, it.name) }
    }
}
