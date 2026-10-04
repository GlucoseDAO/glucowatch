package io.github.antonkulaga.glucowatch.data

import android.content.Context
import glucowatch.core.CacheFormat
import glucowatch.core.link.LinkSnapshot
import org.json.JSONObject

/** Emulator screenshot transport only: the phone's real private snapshot, no login or pairing key. */
object DebugPhoneSnapshot {
    fun read(context: Context): LinkSnapshot? = runCatching {
        val file = context.filesDir.resolve("debug-phone-snapshot.json")
        if (!file.isFile) return null
        val json = JSONObject(file.readText())
        val readings = CacheFormat.decodeReadings(json.getString("readings"))
        require(readings.isNotEmpty() && System.currentTimeMillis() - readings.last().timeMillis in -60_000L..86_400_000L) { "Screenshot snapshot is older than a day" }
        LinkSnapshot(json.getString("upstream"), json.getString("sourceLabel"), readings,
            CacheFormat.decodeTreatments(json.getString("treatments")), CacheFormat.decodeLoop(json.getString("loop")),
            CacheFormat.decodePrediction(json.getString("forecast")), json.optString("error").takeIf(String::isNotEmpty))
    }.getOrNull()
}
