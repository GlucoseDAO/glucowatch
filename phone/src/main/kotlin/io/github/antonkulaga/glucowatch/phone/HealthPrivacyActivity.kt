package io.github.antonkulaga.glucowatch.phone

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** The rationale Health Connect opens from its permissions screen. */
class HealthPrivacyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val pad = (24 * density).toInt()
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(TextView(this@HealthPrivacyActivity).apply {
                text = "Heart-rate privacy"
                textSize = 26f
                setTextColor(0xFFE6EDF0.toInt())
            })
            addView(TextView(this@HealthPrivacyActivity).apply {
                text = "GlucoPhone reads recent heart-rate samples from Health Connect only while you view the phone dashboard. It shows the latest value and its change over about 30 minutes. Heart-rate data stays on this phone; it is not sent to Dexcom, Nightscout, Hugging Face, or a paired watch. You can revoke access in Health Connect settings."
                textSize = 16f
                setTextColor(0xFFB7C8D0.toInt())
                setPadding(0, pad, 0, 0)
            })
        })
    }
}
