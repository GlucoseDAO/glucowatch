package io.github.antonkulaga.glucowatch.phone

import android.app.Activity
import android.content.Intent
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Environment
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Base64
import android.view.ViewGroup
import android.view.MotionEvent
import android.webkit.*
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.JavaScriptReplyProxy
import glucowatch.core.ExportPortal
import glucowatch.core.HistoricalCsv
import glucowatch.core.Region
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

/** A manual portal with CSV capture. Sign-in and date selection stay manual. */
open class ExportPortalActivity : Activity() {
    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var portal: ExportPortal
    private lateinit var country: String
    private lateinit var region: Region
    private lateinit var webContainer: FrameLayout
    private val popupOrigins = linkedMapOf<WebView, String>()
    private var blobWeb: WebView? = null
    private var desktopSite = false
    private var fitToScreen = true
    private val gestures = mutableMapOf<WebView, Long>()
    private lateinit var csvCapture: PortalCsvCapture
    private var captureId: String? = null
    private var captureOrigin: String? = null
    private var expectedBytes = 0
    private var downloading = false
    private var finalizing = false
    private var returned = false
    private var externalLaunched = false
    private var pickerOpen = false
    private val browserPreferences by lazy { getSharedPreferences("export-browser", MODE_PRIVATE) }
    private var blobOutput: java.io.OutputStream? = null
    private var blobBytes = 0
    private val downloadFile by lazy { cacheDir.resolve("$FILE_PREFIX${java.util.UUID.randomUUID()}.csv") }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        portal = runCatching { ExportPortal.valueOf(intent.getStringExtra("portal").orEmpty()) }.getOrElse { finish(); return }
        if (android.os.Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            android.window.OnBackInvokedCallback { navigateBack() },
        )
        ExportDiagnostics.event(this, "portal_activity_created", portal)
        country = intent.getStringExtra("country") ?: "DE"
        region = runCatching { Region.valueOf(intent.getStringExtra("region").orEmpty()) }.getOrDefault(Region.OUS)
        val address = portal.url(country, region)
        csvCapture = PortalCsvCapture(portal, country, region)
        status = TextView(this).apply {
            text = "${portal.label}: ${intent.getStringExtra("start")} to ${intent.getStringExtra("end")}.\nEnter your login and dates on the site, then download CSV. Use English for the data export. The site closes when its CSV is collected."
            setTextColor(Brand.TEXT); textSize = 14f; setPadding(20, 12, 20, 12)
        }
        desktopSite = portal == ExportPortal.CARELINK
        webContainer = FrameLayout(this)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Button(this@ExportPortalActivity).apply {
                text = "Back to export"; Brand.style(this, false); setOnClickListener { finish() }
            })
            addView(TextView(this@ExportPortalActivity).apply {
                text = "Target interval: ${targetDates()} · both dates included"
                textSize = 18f; setTextColor(Brand.TEXT); setTypeface(null, android.graphics.Typeface.BOLD); setPadding(20, 12, 20, 12)
            })
            addView(status)
            addView(LinearLayout(this@ExportPortalActivity).apply {
            addView(CheckBox(this@ExportPortalActivity).apply {
                text = "Desktop site"; setTextColor(Brand.TEXT); isChecked = desktopSite
                buttonTintList = android.content.res.ColorStateList.valueOf(Brand.TEXT)
                setOnCheckedChangeListener { _, checked ->
                    desktopSite = checked
                    if (::web.isInitialized) activeWeb().takeIf { it.parent != null }?.apply { settings.userAgentString = userAgent(this); reload() }
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(CheckBox(this@ExportPortalActivity).apply {
                text = "Fit to screen"; setTextColor(Brand.TEXT); isChecked = true
                buttonTintList = android.content.res.ColorStateList.valueOf(Brand.TEXT)
                setOnCheckedChangeListener { _, checked -> fitToScreen = checked; if (::web.isInitialized) fitPage(activeWeb()) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            })
            addView(Button(this@ExportPortalActivity).apply {
                text = "Reload page"; Brand.style(this, false)
                setOnClickListener {
                    if (preferExternalBrowser()) { if (!openBrowser(address)) reloadPortal(address) }
                    else reloadPortal(address)
                }
            })
            addView(webContainer, LinearLayout.LayoutParams(-1, 0, 1f))
            setOnApplyWindowInsetsListener { v, insets ->
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                }
                insets
            }
        })
        if (preferExternalBrowser()) {
            val dialog = android.app.AlertDialog.Builder(this).setTitle("${portal.label}: dates to select")
                .setMessage("${targetDates()}\nInclude both dates.\n\nSelect this interval on the site, then download the English CSV data export. These dates also remain visible when you return to GlucoPhone.")
                .setPositiveButton("Open site") { _, _ -> if (!openBrowser(address)) loadEmbedded(address) }
                .setNeutralButton("Copy dates", null).setNegativeButton("Back") { _, _ -> finish() }.create()
            dialog.setOnShowListener { dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Report interval", targetDates()))
                Toast.makeText(this, "Target dates copied", Toast.LENGTH_SHORT).show()
            } }
            dialog.show()
        } else loadEmbedded(address)
    }

    private fun targetDates(): String {
        val format = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM uuuu", java.util.Locale.ENGLISH)
        fun date(key: String) = runCatching { java.time.LocalDate.parse(intent.getStringExtra(key)).format(format) }
            .getOrDefault(intent.getStringExtra(key).orEmpty())
        return "${date("start")} to ${date("end")}"
    }
    private fun loadEmbedded(address: String) {
        if (!::web.isInitialized) {
            web = portalWebView(address)
            webContainer.addView(web, FrameLayout.LayoutParams(-1, -1))
        }
        loadPortal(web, address)
    }

    protected open fun preferExternalBrowser() = javaClass == ExportPortalActivity::class.java &&
        browserPreferences.getBoolean("external.${portal.name}", true)
    protected open fun externalAddress(address: String) = address

    private fun selectBrowserCsv() {
        if (pickerOpen || downloading || returned) return
        pickerOpen = true
        status.text = "Select the CSV you just downloaded. The next source opens after import."
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.providers.downloads.documents/root/downloads"))
        }, REQUEST_CSV)
    }

    private fun activeWeb() = popupOrigins.keys.lastOrNull() ?: web

    private fun pageOrigin(view: WebView): String? = ExportPortal.origin(view.url.orEmpty())
        ?: popupOrigins[view]?.takeIf { view.url == null || view.url == "about:blank" }

    private fun userAgent(view: WebView): String {
        val agent = WebSettings.getDefaultUserAgent(view.context).replace("; wv", "").replace("Version/4.0 ", "")
        return if (desktopSite) agent.replace(Regex("\\([^)]*Android[^)]*\\)"), "(Windows NT 10.0; Win64; x64)")
            .replace(" Mobile ", " ") else agent
    }

    private fun portalWebView(initialAddress: String): WebView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportMultipleWindows(true)
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.userAgentString = userAgent(this)
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            setOnTouchListener { view, event ->
                if (event.action == MotionEvent.ACTION_UP) gestures[view as WebView] = SystemClock.elapsedRealtime()
                false
            }
            csvCapture.install(this, ::receiveCsv)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // The provider's captcha/login frames remain normal web content; only main-frame navigation is constrained.
                    if (!request.isForMainFrame) return false
                    if (request.url.scheme == "blob") { requestCsv(view, request.url.toString()); return true }
                    if (request.url.toString() == "about:blank" && view in popupOrigins) return false
                    val allowed = portal.permits(request.url.toString(), country, region)
                    if (!allowed) {
                        status.text = "This link needs a full browser. Opening ${portal.label}."
                        if (javaClass == ExportPortalActivity::class.java) openBrowser(initialAddress)
                    }
                    return !allowed
                }
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    interceptPortalRequest(request)

                override fun onPageStarted(view: WebView, url: String, icon: android.graphics.Bitmap?) {
                    if (captureId != null && blobWeb === view && !url.startsWith("blob:"))
                        failBlob("The download page changed before its CSV was collected.")
                }
                override fun onPageFinished(view: WebView, url: String) {
                    fitPage(view)
                    handler.postDelayed({ if (view.parent != null) fitPage(view) }, 750)
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) {
                        status.text = "${portal.label} could not load (browser error ${error.errorCode}). Check the connection."
                        if (javaClass == ExportPortalActivity::class.java) openBrowser(initialAddress)
                    }
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    if (blobWeb === view) failBlob("The portal renderer closed during CSV capture.")
                    csvCapture.remove(view)
                    popupOrigins.remove(view); gestures.remove(view)
                    (view.parent as? ViewGroup)?.removeView(view); view.destroy()
                    status.text = "The portal page stopped. Opening the browser. Your export dates are kept."
                    if (javaClass == ExportPortalActivity::class.java) openBrowser(initialAddress)
                    return true
                }
            }
            webViewRenderProcessClient = object : WebViewRenderProcessClient() {
                override fun onRenderProcessUnresponsive(view: WebView, renderer: WebViewRenderProcess?) {
                    status.text = "The portal page is not responding. Opening the browser. Your export dates are kept."
                    if (javaClass == ExportPortalActivity::class.java) openBrowser(initialAddress)
                }
                override fun onRenderProcessResponsive(view: WebView, renderer: WebViewRenderProcess?) {}
            }
            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, result: Message): Boolean {
                    val origin = pageOrigin(view) ?: return false
                    val recentGesture = SystemClock.elapsedRealtime() - (gestures[view] ?: 0) < 10_000
                    if ((!isUserGesture && !recentGesture) || !portal.permits(origin, country, region)) return false
                    val popup = portalWebView(initialAddress)
                    popupOrigins[popup] = origin
                    webContainer.addView(popup, FrameLayout.LayoutParams(-1, -1))
                    (result.obj as WebView.WebViewTransport).webView = popup
                    result.sendToTarget()
                    return true
                }
                override fun onCloseWindow(window: WebView) { closePopup(window) }
            }
            setDownloadListener { url, agent, disposition, mime, _ ->
                when {
                    mime.orEmpty().contains("pdf", true) -> status.text = "Choose Data export CSV to import these dates."
                    url.startsWith("blob:") -> requestCsv(this, url)
                    mime.orEmpty().contains("csv", true) || disposition.orEmpty().contains(".csv", true) ||
                        Uri.parse(url).path.orEmpty().endsWith(".csv", true) -> requestCsv(this, url)
                    else -> status.text = "Choose CSV data export on ${portal.label}. PDF can be generated in GlucoPhone after import."
                }
            }
        }

    private fun requestCsv(view: WebView, address: String) {
        if (downloading || returned) return
        if (!address.startsWith("blob:") && !portal.permits(address, country, region, download = true)) {
            status.text = "The selected download is outside this portal."; return
        }
        status.text = "Collecting the CSV from the signed-in page…"
        if (!csvCapture.request(view, address)) {
            status.text = "CSV capture needs a newer Android System WebView. Opening the browser."
            if (javaClass == ExportPortalActivity::class.java) openBrowser(portal.url(country, region))
        }
    }

    private fun receiveCsv(view: WebView, origin: String, data: String, reply: JavaScriptReplyProxy) {
        if (finalizing || returned) { runCatching { reply.postMessage("reject") }; return }
        try {
            val message = org.json.JSONObject(data)
            val id = message.getString("id")
            if (captureId != null && (message.getString("kind") != "start") &&
                (captureId != id || blobWeb !== view || captureOrigin != origin)) {
                runCatching { reply.postMessage("reject") }; return
            }
            when (message.getString("kind")) {
                "start" -> {
                    if (downloading || returned) { runCatching { reply.postMessage("reject") }; return }
                    require(id.length in 1..80)
                    expectedBytes = message.getInt("size")
                    require(expectedBytes in 1..HistoricalCsv.MAX_BYTES)
                    captureId = id; captureOrigin = origin; blobWeb = view
                    blobBytes = 0; blobOutput = downloadFile.outputStream(); downloading = true
                    status.text = "Collecting ${portal.label} CSV…"
                    handler.postDelayed({ if (captureId == id) failBlob("CSV capture timed out.") }, 90_000)
                }
                "chunk" -> {
                    require(captureId == id && captureOrigin == origin && blobWeb === view)
                    val chunk = message.getString("chunk")
                    require(chunk.length <= 65_536)
                    val bytes = Base64.decode(chunk, Base64.NO_WRAP)
                    blobBytes += bytes.size
                    require(blobBytes <= expectedBytes)
                    blobOutput!!.write(bytes)
                }
                "done" -> {
                    require(captureId == id && captureOrigin == origin && blobWeb === view && blobBytes == expectedBytes)
                    closeBlob(); complete()
                }
                "error" -> {
                    if (captureId != null && captureId != id) { runCatching { reply.postMessage("reject") }; return }
                    val reason = when (message.optString("reason")) {
                        "http" -> "The signed-in page's CSV request failed (HTTP ${message.optInt("status")}). Retry its data export after signing in."
                        "format" -> "The page did not produce an English CSV data export. Choose Data export CSV."
                        "size" -> "CSV exceeds 20 MB. Choose fewer days."
                        "timeout" -> "The page did not finish transferring CSV. Retry the data export."
                        else -> "The CSV is no longer available in this page. Reload, then click Data export CSV again."
                    }
                    failBlob(reason)
                    runCatching { reply.postMessage("reject") }; return
                }
                else -> error("Unexpected CSV message")
            }
            runCatching { reply.postMessage("ok") }
        } catch (_: Exception) {
            if (captureId != null && blobWeb === view && captureOrigin == origin) failBlob("CSV capture was interrupted or exceeded its declared size.")
            runCatching { reply.postMessage("reject") }
        }
    }

    private fun fitPage(view: WebView) {
        if (view.parent == null) return
        val logicalWidth = (view.width / resources.displayMetrics.density).toInt().coerceAtLeast(1)
        view.evaluateJavascript("""
            (() => {
              if (!document.head) return;
              let meta=document.querySelector('meta[name=viewport]');
              if (!window.__glucoViewport) window.__glucoViewport={content:meta ? meta.content : null};
              if (!${fitToScreen}) {
                if (meta && window.__glucoViewport.content !== null) meta.content=window.__glucoViewport.content;
                else if (meta) meta.remove();
                return;
              }
              const width=Math.max($logicalWidth,document.documentElement.scrollWidth,document.body ? document.body.scrollWidth : 0);
              if (!meta) { meta=document.createElement('meta'); meta.name='viewport'; document.head.appendChild(meta); }
              meta.content='width='+width+',initial-scale='+($logicalWidth/width)+',minimum-scale=0.05,maximum-scale=5,user-scalable=yes';
            })();
        """.trimIndent(), null)
    }

    private fun openBrowser(address: String): Boolean {
        if (externalLaunched || returned || downloading) return externalLaunched
        val opened = ExportBrowser.open(this, externalAddress(address), REQUEST_BROWSER, preferChromium = portal == ExportPortal.CARELINK)
        if (opened) {
            ExportDiagnostics.event(this, "external_browser_opened", portal)
            externalLaunched = true
            browserPreferences.edit().putBoolean("external.${portal.name}", true).apply()
            status.text = "Download ${portal.label} CSV in the browser, then open or share that CSV with GlucoPhone. Closing the browser opens the file picker directly."
        } else status.text = "A full browser is needed for downloads. The WebView test shell cannot save files; using the built-in portal."
        return opened
    }

    private fun reloadPortal(address: String) {
        if (!::web.isInitialized) { loadEmbedded(address); return }
        if (downloading) failBlob("The current CSV capture was cancelled for reload.")
        popupOrigins.keys.toList().forEach(::closePopup)
        csvCapture.remove(web); gestures.remove(web)
        (web.parent as? ViewGroup)?.removeView(web); runCatching { web.destroy() }
        web = portalWebView(address)
        webContainer.addView(web, FrameLayout.LayoutParams(-1, -1))
        status.text = "Reopening ${portal.label}…"
        loadPortal(web, address)
    }

    private fun closePopup(view: WebView) {
        if (popupOrigins.remove(view) == null) return
        if (blobWeb === view) failBlob("The CSV window closed before the download finished.")
        csvCapture.remove(view); gestures.remove(view)
        webContainer.removeView(view); view.stopLoading(); view.destroy()
    }

    @Deprecated("Legacy dispatch for Android 10-12; Android 13+ uses OnBackInvokedDispatcher")
    @android.annotation.SuppressLint("GestureBackNavigation") // Modern callback is registered in onCreate.
    override fun onBackPressed() = navigateBack()

    private fun navigateBack() {
        if (!::web.isInitialized) { finish(); return }
        val current = activeWeb()
        when {
            current.parent != null && current.canGoBack() -> current.goBack()
            current !== web -> closePopup(current)
            else -> finish()
        }
    }

    protected open fun interceptPortalRequest(request: WebResourceRequest): WebResourceResponse? = null

    protected open fun loadPortal(view: WebView, address: String) { view.loadUrl(address) }

    private fun closeBlob() {
        runCatching { blobOutput?.close() }; blobOutput = null
        blobBytes = 0
        blobWeb = null
        captureId = null; captureOrigin = null; expectedBytes = 0
    }

    private fun failBlob(reason: String) {
        closeBlob(); downloadFile.delete(); downloading = false
        status.text = reason
        if (!isFinishing && javaClass == ExportPortalActivity::class.java) openBrowser(portal.url(country, region))
    }

    private fun collect(read: suspend () -> Unit) {
        if (downloading) return
        downloading = true; status.text = "Collecting ${portal.label} CSV…"
        scope.launch {
            try { withContext(Dispatchers.IO) { read() }; complete() }
            catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { status.text = e.message ?: "The portal returned an invalid CSV download." }
            catch (e: java.io.IOException) { status.text = "Could not read CSV. Check the connection or select a downloaded file." }
            catch (_: Exception) { status.text = "Could not read CSV. Try again or select the downloaded file below." }
            finally {
                if (!returned && !finalizing) downloadFile.delete()
                if (!finalizing) downloading = false
            }
        }
    }

    private fun copyCsv(input: java.io.InputStream, context: CoroutineContext) {
        downloadFile.outputStream().use { output ->
            val buffer = ByteArray(8192); var total = 0
            while (true) {
                context.ensureActive()
                val n = input.read(buffer); if (n < 0) break
                total += n; require(total <= HistoricalCsv.MAX_BYTES); output.write(buffer, 0, n)
            }
            require(total > 0)
        }
    }

    private fun complete() {
        if (finalizing || returned) return
        finalizing = true
        downloading = true
        status.text = "Saving ${portal.label} CSV to Downloads/GlucoPhone…"
        scope.launch {
            try {
                val name = withContext(Dispatchers.IO) { saveDownloadedCsv() }
                returned = true
                Toast.makeText(this@ExportPortalActivity, "CSV saved to Downloads/GlucoPhone/Raw: $name", Toast.LENGTH_LONG).show()
                setResult(RESULT_OK, Intent().putExtra("csvFile", downloadFile.name).putExtra("source", portal.source)
                    .putExtra("downloadName", name))
                finish()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                status.text = "CSV was collected, but could not be saved to Downloads/GlucoPhone. Check free space and try again."
            }
            finally { finalizing = false; downloading = false }
        }
    }

    /** Scoped-storage download owned by this app; no broad storage permission is needed. */
    private suspend fun saveDownloadedCsv(): String {
        val bytes = downloadFile.readBytes()
        val source = HistoricalCsv.source(bytes.toString(Charsets.UTF_8))
        val actual = ExportPortal.entries.single { it.source == source }
        val uri = ReportFiles.archiveCsv(this, actual, bytes,
            synthetic = BuildConfig.DEBUG && javaClass.simpleName == "ExportPortalPreviewActivity")
        return contentResolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "download.csv"
    }

    @Deprecated("One foreground document import")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_BROWSER && externalLaunched) {
            externalLaunched = false
            selectBrowserCsv()
            return
        }
        if (requestCode == REQUEST_CSV) pickerOpen = false
        if (requestCode == REQUEST_CSV && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            collect {
                val context = currentCoroutineContext()
                contentResolver.openInputStream(uri)?.use { copyCsv(it, context) } ?: error("No CSV")
            }
        }
    }

    override fun onDestroy() {
        scope.cancel(); handler.removeCallbacksAndMessages(null); closeBlob()
        popupOrigins.keys.toList().forEach(::closePopup)
        if (::web.isInitialized) {
            csvCapture.remove(web)
            if (web.parent != null) { web.stopLoading(); (web.parent as? ViewGroup)?.removeView(web); web.destroy() }
        }
        if (!returned) downloadFile.delete()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_CSV = 1
        private const val REQUEST_BROWSER = 2
        const val FILE_PREFIX = "portal-export-"
        fun validFileName(value: String) = value.matches(Regex("portal-export-[0-9a-fA-F-]{36}\\.csv"))
    }
}
