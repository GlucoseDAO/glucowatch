package io.github.antonkulaga.glucowatch.ui

import android.app.Activity
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import glucowatch.core.FailureKind
import glucowatch.core.FailureRecord
import glucowatch.core.Probe
import io.github.antonkulaga.glucowatch.data.GlucoseRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Walks the connection one layer at a time and shows where it stopped, next to what the watch's
 * network looks like and the failures the last fetches ran into.
 *
 * This is the screen to open when readings stop on mobile data but not on Wi-Fi. A name that does
 * not resolve, a connection that never opens on an IPv6-only link and a handshake that is refused
 * are three different problems with three different answers, and they are indistinguishable from
 * the one "no readings" the rest of the app can show.
 */
class DiagnosticsActivity : Activity() {
    private val scope = MainScope()
    private lateinit var column: LinearLayout
    private lateinit var output: LinearLayout
    private lateinit var run: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen = resources.displayMetrics.widthPixels
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((screen * 0.11).toInt(), (screen * 0.17).toInt(), (screen * 0.11).toInt(), (screen * 0.3).toInt())
        }
        run = Button(this).apply {
            text = "Run check"; Brand.style(this, primary = true)
            setOnClickListener { check() }
        }
        output = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        column.addView(heading("Connection check"))
        column.addView(note(
            "Each step runs only when the one before it worked, so the first failure names the layer to fix. " +
                "If the name does not resolve, the check also asks the DoH resolver, which tells that resolver " +
                "this watch looks up Dexcom.",
        ))
        column.addView(run)
        column.addView(output)
        history().forEach(column::addView)
        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun check() {
        run.isEnabled = false
        output.removeAllViews()
        output.addView(note("Checking…"))
        scope.launch {
            val (facts, probes) = withContext(Dispatchers.IO) { GlucoseRepository(applicationContext).checkConnection() }
            output.removeAllViews()
            output.addView(heading("This network"))
            facts.forEach { (name, value) -> output.addView(row(name, value, null)) }
            if (probes.isEmpty()) {
                output.addView(note("Demo data and the phone app do not reach the internet from the watch."))
            } else {
                output.addView(heading("Steps"))
                probes.forEach(::add)
                output.addView(note(verdict(probes)))
            }
            run.isEnabled = true
        }
    }

    private fun add(probe: Probe) {
        val timing = if (probe.millis > 0) " · ${probe.millis} ms" else ""
        output.addView(row("${if (probe.ok) "✓" else "✗"} ${probe.step}$timing", probe.detail, probe.ok))
        probe.kind?.hint?.takeIf { it.isNotEmpty() }?.let { output.addView(note(it)) }
    }

    /** What the first failing step means for the watch, in the words the settings screen can act on. */
    private fun verdict(probes: List<Probe>): String {
        val failed = probes.firstOrNull { !it.ok } ?: return "Dexcom is reachable from this watch right now."
        return when (failed.kind) {
            FailureKind.DNS -> "This network's DNS will not answer for Dexcom. Turn on the DoH fallback in settings, " +
                "or set Private DNS on the watch."
            FailureKind.ROUTE -> "The address resolved but nothing accepted the connection. On an IPv6-only link " +
                "an IPv4-only server needs NAT64; otherwise the route itself is blocked, and only a proxy or another network helps."
            FailureKind.RESET -> "Something on the path closed the connection. That is filtering by server name, " +
                "which a different resolver cannot get around — use a proxy or another network."
            FailureKind.TLS -> "The certificate was not Dexcom's, or the handshake was refused. Do not enter the login on this network."
            FailureKind.TIMEOUT -> "The server accepted the connection and then went quiet. Usually the server's own trouble."
            else -> "Dexcom answered, with: ${failed.detail}"
        }
    }

    private fun history(): List<TextView> {
        val failures = GlucoseRepository(applicationContext).state().failures
        if (failures.isEmpty()) return listOf(heading("Recent failures"), note("None since the app last started."))
        return listOf(heading("Recent failures")) + failures.reversed().map { row(stamp(it), "${it.kind.label}${route(it)}", false) }
    }

    private fun stamp(record: FailureRecord) =
        DateFormat.getTimeFormat(this).format(record.timeMillis)

    private fun route(record: FailureRecord) = record.transport.ifEmpty { null }?.let { " on $it" }.orEmpty()

    private fun row(name: String, value: String, ok: Boolean?) = TextView(this).apply {
        text = "$name\n$value"
        textSize = 12f
        setPadding(0, dp(4), 0, dp(4))
        setTextColor(when (ok) {
            true -> Brand.CARBS
            false -> Brand.LOW
            null -> Brand.TEXT
        })
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(Brand.TEXT)
        setPadding(0, dp(10), 0, dp(2))
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(Brand.MUTED)
        setPadding(0, dp(2), 0, dp(6))
    }

    private fun dp(value: Int) =
        (value * resources.displayMetrics.density).toInt()
}
