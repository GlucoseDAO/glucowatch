package io.github.antonkulaga.glucowatch.phone

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import glucowatch.core.CareLinkAuthorization
import glucowatch.core.CareLinkFormFill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Medtronic's page owns the captcha. A debug build can fill the username and password from `.env`. */
class CareLinkSignInActivity : Activity() {
    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("carelink-sign-in", MODE_PRIVATE) }
    private lateinit var status: TextView
    private var webView: WebView? = null
    private var fillTask: Runnable? = null
    private var completing = false

    private val prefill: Boolean
        get() = BuildConfig.DEBUG && BuildConfig.DEV_CARELINK_USERNAME.isNotBlank() &&
            BuildConfig.DEV_CARELINK_PASSWORD.isNotEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 18f; setTextColor(Brand.TEXT); text = "Opening CareLink sign-in…" }
        showChrome()
        when {
            intent.data != null -> complete(intent.data.toString())
            pending() != null && savedInstanceState != null -> showSignIn()
            else -> scope.launch {
                runCatching { withContext(Dispatchers.IO) { CareLinkAuthorization().begin(intent.getStringExtra("country") ?: "DE") } }
                    .onSuccess { prefs.edit().putString("pending", it.encode()).commit(); showSignIn() }
                    .onFailure { status.text = it.message ?: "Could not open CareLink sign-in" }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { complete(it.toString()) }
    }

    private fun pending() = CareLinkAuthorization.Pending.decode(prefs.getString("pending", null))

    private fun showChrome() {
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 100, 40, 40)
            setBackgroundColor(Brand.BACKGROUND)
            addView(status)
            addView(Button(this@CareLinkSignInActivity).apply {
                text = "Open sign-in browser"; Brand.style(this, true)
                setOnClickListener { pending()?.let { openExternal(it) } }
            })
            addView(Button(this@CareLinkSignInActivity).apply {
                text = "Cancel"; Brand.style(this, false)
                setOnClickListener { cancelSignIn() }
            })
        })
    }

    private fun showSignIn() {
        val pending = pending() ?: return
        if (prefill) showPrefilled(pending) else openExternal(pending)
    }

    private fun showPrefilled(pending: CareLinkAuthorization.Pending) {
        stopFill()
        webView?.destroy()
        val web = WebView(this)
        webView = web
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = true
        // Auth0 treats the default WebView marker as a bot. A normal Chrome header lets the captcha load.
        web.settings.userAgentString = CHROME
        web.setBackgroundColor(Brand.BACKGROUND)
        val script = CareLinkFormFill.script(BuildConfig.DEV_CARELINK_USERNAME, BuildConfig.DEV_CARELINK_PASSWORD)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = takeRedirect(request.url.toString(), pending)

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                takeRedirect(url, pending)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!takeRedirect(url, pending)) view.evaluateJavascript(script, null)
            }
        }
        status.text = "Username and password are filled in. Solve the captcha and sign in."
        (status.parent as? ViewGroup)?.removeView(status)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Brand.BACKGROUND)
            addView(status.apply { setPadding(32, 48, 32, 16) })
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(Button(this@CareLinkSignInActivity).apply {
                text = "Open in browser instead"; Brand.style(this, false)
                setOnClickListener { openExternal(pending) }
            })
            addView(Button(this@CareLinkSignInActivity).apply {
                text = "Cancel"; Brand.style(this, false)
                setOnClickListener { cancelSignIn() }
            })
        })
        web.loadUrl(pending.url)
        val task = object : Runnable {
            override fun run() {
                if (isDestroyed || webView !== web) return
                web.evaluateJavascript(script, null)
                handler.postDelayed(this, 500)
            }
        }
        fillTask = task
        handler.postDelayed(task, 500)
    }

    private fun takeRedirect(url: String, pending: CareLinkAuthorization.Pending): Boolean {
        if (!url.startsWith(pending.redirect)) return false
        webView?.stopLoading()
        complete(url)
        return true
    }

    private fun openExternal(pending: CareLinkAuthorization.Pending) {
        stopFill()
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pending.url)).addCategory(Intent.CATEGORY_BROWSABLE)) }
            .onSuccess { status.text = "Finish signing in in your browser. CareLink will return you to GlucoPhone." }
            .onFailure { status.text = "Install a web browser to sign in to CareLink." }
    }

    private fun cancelSignIn() {
        stopFill()
        prefs.edit().clear().apply()
        finish()
    }

    private fun complete(callback: String) {
        if (completing) return
        val pending = pending() ?: return run { status.text = "Start a CareLink sign-in from Connect first." }
        completing = true
        stopFill()
        status.text = "Finishing CareLink sign-in…"
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { CareLinkAuthorization().finish(pending, callback) } }
                .onSuccess { token ->
                    val account = token.subject ?: run {
                        status.text = "CareLink returned no account identifier. Sign in again."
                        completing = false
                        return@onSuccess
                    }
                    PhoneCareLinkStore(this@CareLinkSignInActivity).save(token)
                    val store = PhoneSettingsStore(this@CareLinkSignInActivity)
                    store.save(store.load().copy(carelinkAccount = account, carelinkCountry = token.country))
                    prefs.edit().clear().apply()
                    startActivity(Intent(this@CareLinkSignInActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish()
                }
                .onFailure { status.text = it.message ?: "CareLink sign-in failed"; completing = false }
        }
    }

    private fun stopFill() {
        fillTask?.let { handler.removeCallbacks(it) }
        fillTask = null
    }

    override fun onDestroy() {
        stopFill()
        webView?.apply { stopLoading(); destroy() }
        webView = null
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val CHROME =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
