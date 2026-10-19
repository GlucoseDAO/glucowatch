package io.github.antonkulaga.glucowatch.phone

import android.content.Context
import android.util.Log
import glucowatch.core.ExportPortal
import org.json.JSONObject

/** Local debug journal: no filenames, URIs, account details, CSV rows or exception messages. */
internal object ExportDiagnostics {
    private const val MAX_BYTES = 128 * 1024
    private var previous: String? = null
    private var previousAt = 0L

    @Synchronized
    fun event(context: Context, event: String, portal: ExportPortal? = null, bytes: Long? = null,
              fileKey: String? = null, stage: String? = null, error: Throwable? = null) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val data = JSONObject().put("event", event)
            portal?.let { data.put("portal", it.name) }
            bytes?.let { data.put("bytes", it) }
            // The caller passes an opaque document fingerprint; never retain the original.
            fileKey?.let { data.put("fileKey", it.hashCode().toUInt().toString(16)) }
            stage?.let { data.put("stage", it) }
            error?.let { data.put("errorType", it.javaClass.simpleName) }
            val signature = data.toString()
            val now = android.os.SystemClock.elapsedRealtime()
            if (signature == previous && now - previousAt < 5_000) return
            previous = signature; previousAt = now
            data.put("time", System.currentTimeMillis())
            val line = data.toString()
            Log.i("GlucoExport", line)
            val file = context.filesDir.resolve("export-diagnostics.jsonl")
            if (file.length() >= MAX_BYTES) file.writeText("")
            file.appendText(line + "\n")
        }
    }
}
