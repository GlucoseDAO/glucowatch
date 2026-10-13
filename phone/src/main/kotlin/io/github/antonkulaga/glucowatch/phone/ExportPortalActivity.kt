package io.github.antonkulaga.glucowatch.phone

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Base64
import android.view.ViewGroup
import android.webkit.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import glucowatch.core.ExportPortal
import glucowatch.core.HistoricalCsv
import glucowatch.core.Region
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URI

/** A manual portal, with download capture only. Never fills, submits, or polls a login/date form. */
open class ExportPortalActivity : Activity() {
    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var portal: ExportPortal
    private lateinit var country: String
    private lateinit var region: Region
    private var downloading = false
    private var returned = false
    private var blobPort: WebMessagePort? = null
    private var blobOutput: java.io.OutputStream? = null
    private var blobBytes = 0
    private val downloadFile by lazy { cacheDir.resolve("$FILE_PREFIX${java.util.UUID.randomUUID()}.csv") }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        portal = runCatching { ExportPortal.valueOf(intent.getStringExtra("portal").orEmpty()) }.getOrElse { finish(); return }
        country = intent.getStringExtra("country") ?: "DE"
        region = runCatching { Region.valueOf(intent.getStringExtra("region").orEmpty()) }.getOrDefault(Region.OUS)
        val address = portal.url(country, region)
        status = TextView(this).apply {
            text = "${portal.label}: ${intent.getStringExtra("start")} to ${intent.getStringExtra("end")}.\nEnter your login and dates on the site, then download CSV. Use English for the data export. The site closes when its CSV is collected."
            setTextColor(Brand.TEXT); textSize = 14f; setPadding(20, 12, 20, 12)
        }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportMultipleWindows(false)
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.userAgentString = settings.userAgentString.replace("; wv", "")
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // The provider's captcha/login frames remain normal web content; only main-frame navigation is constrained.
                    if (!request.isForMainFrame) return false
                    val allowed = portal.permits(request.url.toString(), country, region)
                    if (!allowed) status.text = "This link is outside ${portal.label}. Open the site in your browser, then select the downloaded CSV below."
                    return !allowed
                }
                override fun onPageStarted(view: WebView, url: String, icon: android.graphics.Bitmap?) {
                    if (blobPort != null) failBlob()
                }
            }
            setDownloadListener { url, agent, disposition, mime, _ ->
                when {
                    url.startsWith("blob:") -> downloadBlob(url)
                    mime.contains("csv", true) || disposition.contains(".csv", true) -> download(url, agent)
                    else -> status.text = "Choose CSV data export on ${portal.label}. PDF can be generated in GlucoPhone after import."
                }
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Button(this@ExportPortalActivity).apply {
                text = "Back to export"; Brand.style(this, false); setOnClickListener { finish() }
            })
            addView(status)
            addView(LinearLayout(this@ExportPortalActivity).apply {
                addView(Button(this@ExportPortalActivity).apply {
                    text = "Open in browser"; Brand.style(this, false)
                    setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address))) }
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(Button(this@ExportPortalActivity).apply {
                    text = "Select downloaded CSV"; Brand.style(this, false)
                    setOnClickListener {
                        if (!downloading) startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                            putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.providers.downloads.documents/root/downloads"))
                        }, REQUEST_CSV)
                    }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            })
            addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
            setOnApplyWindowInsetsListener { v, insets ->
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                }
                insets
            }
        })
        loadPortal(web, address)
    }

    protected open fun loadPortal(view: WebView, address: String) { view.loadUrl(address) }

    /** HTTPS attachments keep the clicked URL's cookies and never follow a redirect with them. */
    private fun download(address: String, agent: String) {
        if (downloading) return
        if (!portal.permits(address, country, region, download = true)) { status.text = "Expected a CSV from the selected portal."; return }
        val cookies = CookieManager.getInstance().getCookie(address).orEmpty()
        collect {
            val connection = URI(address).toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000; connection.readTimeout = 90_000
                connection.setRequestProperty("User-Agent", agent)
                connection.setRequestProperty("Cookie", cookies)
                require(connection.responseCode == 200)
                require(connection.contentLengthLong <= HistoricalCsv.MAX_BYTES)
                connection.inputStream.use { copyCsv(it) }
            } finally { connection.disconnect() }
        }
    }

    /** One-shot capture of the blob the user downloaded. The port is transferred to its exact top-frame origin. */
    private fun downloadBlob(address: String) {
        if (downloading) return
        val origin = ExportPortal.origin(web.url.orEmpty()) ?: return
        if (ExportPortal.origin(address.removePrefix("blob:")) != origin ||
            !portal.permits(origin, country, region, download = true)) {
            status.text = "Select the portal's downloaded CSV below."; return
        }
        downloading = true; status.text = "Collecting ${portal.label} CSV…"
        val nonce = java.util.UUID.randomUUID().toString()
        val ports = web.createWebMessageChannel()
        blobPort = ports[0]
        ports[0].setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
            override fun onMessage(port: WebMessagePort, message: WebMessage) {
                if (blobPort !== ports[0]) return
                try {
                    val data = message.data ?: error("Empty CSV message")
                    when {
                        data == "start" && blobOutput == null -> blobOutput = downloadFile.outputStream()
                        data.startsWith("chunk:") && blobOutput != null -> {
                            require(data.length <= 65_542)
                            val bytes = Base64.decode(data.substring(6), Base64.NO_WRAP)
                            blobBytes += bytes.size; require(blobBytes <= HistoricalCsv.MAX_BYTES)
                            blobOutput!!.write(bytes)
                        }
                        data == "done" && blobOutput != null && blobBytes > 0 -> {
                            closeBlob(); complete(); return
                        }
                        else -> error("Invalid CSV message")
                    }
                    port.postMessage(WebMessage("ok"))
                } catch (_: Exception) { failBlob() }
            }
        })
        // No page-load scripts, field filling, credential access, polling, or general JavaScript bridge.
        val script = """
            (function() {
              const nonce = ${org.json.JSONObject.quote(nonce)};
              function receive(event) {
                if (event.data !== nonce || !event.ports[0]) return;
                window.removeEventListener('message', receive);
                const port = event.ports[0];
                const send = data => new Promise(resolve => { port.onmessage = resolve; port.postMessage(data); });
                (async function() {
                  try {
                    const blob = await (await fetch(${org.json.JSONObject.quote(address)})).blob();
                    if (!blob.size || blob.size > ${HistoricalCsv.MAX_BYTES}) throw Error('CSV size');
                    await send('start');
                    for (let offset = 0; offset < blob.size; offset += 49152) {
                      const bytes = new Uint8Array(await blob.slice(offset, offset + 49152).arrayBuffer());
                      await send('chunk:' + btoa(String.fromCharCode.apply(null, bytes)));
                    }
                    port.postMessage('done');
                  } catch (_) { port.postMessage('error'); }
                })();
              }
              window.addEventListener('message', receive);
            })();
        """.trimIndent()
        web.evaluateJavascript(script) {
            if (blobPort != null && ExportPortal.origin(web.url.orEmpty()) == origin)
                web.postWebMessage(WebMessage(nonce, arrayOf(ports[1])), Uri.parse(origin))
            else runCatching { ports[1].close() }
        }
        handler.postDelayed({ if (blobPort === ports[0]) failBlob() }, 90_000)
    }

    private fun closeBlob() {
        runCatching { blobOutput?.close() }; blobOutput = null
        runCatching { blobPort?.close() }; blobPort = null
        blobBytes = 0
    }

    private fun failBlob() {
        closeBlob(); downloadFile.delete(); downloading = false
        status.text = "Could not collect that CSV. Open the site in your browser, download CSV, then select it below."
    }

    private fun collect(read: suspend () -> Unit) {
        if (downloading) return
        downloading = true; status.text = "Collecting ${portal.label} CSV…"
        scope.launch {
            try { withContext(Dispatchers.IO) { read() }; complete() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { status.text = "Could not read CSV. Try again or select the downloaded file below." }
            finally { if (!returned) downloadFile.delete(); downloading = false }
        }
    }

    private suspend fun copyCsv(input: java.io.InputStream) {
        downloadFile.outputStream().use { output ->
            val buffer = ByteArray(8192); var total = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer); if (n < 0) break
                total += n; require(total <= HistoricalCsv.MAX_BYTES); output.write(buffer, 0, n)
            }
            require(total > 0)
        }
    }

    private fun complete() {
        returned = true
        setResult(RESULT_OK, Intent().putExtra("csvFile", downloadFile.name).putExtra("source", portal.source))
        finish()
    }

    @Deprecated("One foreground document import")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CSV && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            collect { contentResolver.openInputStream(uri)?.use { copyCsv(it) } ?: error("No CSV") }
        }
    }

    override fun onDestroy() {
        scope.cancel(); handler.removeCallbacksAndMessages(null); closeBlob()
        if (::web.isInitialized) {
            web.stopLoading(); (web.parent as? ViewGroup)?.removeView(web); web.destroy()
        }
        if (!returned) downloadFile.delete()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_CSV = 1
        const val FILE_PREFIX = "portal-export-"
        fun validFileName(value: String) = value.matches(Regex("portal-export-[0-9a-fA-F-]{36}\\.csv"))
    }
}
