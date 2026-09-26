package io.github.antonkulaga.glucowatch.phone

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import glucowatch.core.CareLinkAuthorization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The system browser owns the password/CAPTCHA page. Only the validated OAuth result returns here. */
class CareLinkSignInActivity : Activity() {
    private val scope = MainScope()
    private val prefs by lazy { getSharedPreferences("carelink-sign-in", MODE_PRIVATE) }
    private lateinit var status: TextView
    private var completing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 18f; setTextColor(Brand.TEXT); text = "Opening CareLink sign-in…" }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 100, 40, 40)
            setBackgroundColor(Brand.BACKGROUND)
            addView(status)
            addView(Button(this@CareLinkSignInActivity).apply {
                text = "Open sign-in browser"; Brand.style(this, true)
                setOnClickListener { openBrowser() }
            })
            addView(Button(this@CareLinkSignInActivity).apply {
                text = "Cancel"; Brand.style(this, false)
                setOnClickListener { prefs.edit().clear().apply(); finish() }
            })
        })
        if (intent.data != null) complete(intent.data.toString())
        else if (savedInstanceState != null && pending() != null) status.text = "Finish signing in in your browser."
        else scope.launch {
            runCatching { withContext(Dispatchers.IO) { CareLinkAuthorization().begin(intent.getStringExtra("country") ?: "DE") } }
                .onSuccess { prefs.edit().putString("pending", it.encode()).commit(); openBrowser() }
                .onFailure { status.text = it.message ?: "Could not open CareLink sign-in" }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { complete(it.toString()) }
    }

    private fun pending() = CareLinkAuthorization.Pending.decode(prefs.getString("pending", null))

    private fun openBrowser() {
        val pending = pending() ?: return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pending.url)).addCategory(Intent.CATEGORY_BROWSABLE)) }
            .onSuccess { status.text = "Finish signing in in your browser. CareLink will return you to GlucoPhone." }
            .onFailure { status.text = "Install a web browser to sign in to CareLink." }
    }

    private fun complete(callback: String) {
        if (completing) return
        val pending = pending() ?: return run { status.text = "Start a CareLink sign-in from Connect first." }
        completing = true
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
                    store.save(store.load().copy(carelinkAccount = account))
                    prefs.edit().clear().apply()
                    startActivity(Intent(this@CareLinkSignInActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish()
                }
                .onFailure { status.text = it.message ?: "CareLink sign-in failed"; completing = false }
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
