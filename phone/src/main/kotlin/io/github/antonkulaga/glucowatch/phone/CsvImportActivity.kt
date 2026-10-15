package io.github.antonkulaga.glucowatch.phone

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/** Explicit browser/Files handoff. Clearing above Main also closes its portal Custom Tab. */
class CsvImportActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        @Suppress("DEPRECATION")
        val uri = if (intent.action == Intent.ACTION_SEND) intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) else intent.data
        if (uri?.scheme == "content") {
            startActivity(Intent(this, MainActivity::class.java).setAction(ACTION_IMPORT).setData(uri)
                .apply { clipData = ClipData.newRawUri("CSV", uri) }
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        finish()
    }
    companion object { const val ACTION_IMPORT = "io.github.antonkulaga.glucowatch.phone.IMPORT_HISTORY_CSV" }
}
