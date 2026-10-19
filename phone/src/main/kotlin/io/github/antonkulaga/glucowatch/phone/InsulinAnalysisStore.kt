package io.github.antonkulaga.glucowatch.phone

import android.content.Context
import glucowatch.core.*
import glucowatch.core.link.LinkSource
import org.json.JSONObject
import java.time.ZoneId

/** Account-bound historical snapshot. Its timestamp is always displayed beside its values. */
class InsulinAnalysisStore(context: Context) {
    private val prefs=context.getSharedPreferences("insulin-history-analysis",Context.MODE_PRIVATE)
    private fun account(s: PhoneSettings)=DebugDefaults.fingerprint("${s.configurationKey}|${s.keyOf(LinkSource.CARELINK)}|${s.carelinkCountry}")
    data class Snapshot(val point: InsulinAnalytics.Point,val responseCount: Int)
    fun save(rows: List<ExportRow>,glucose: List<GlucoseReading>,zone: ZoneId,time: Long,settings: PhoneSettings) {
        val p=InsulinAnalytics.at(rows,zone,time)
        val data=JSONObject().put("account",account(settings)).put("time",time)
            .put("isf",p.recordedIsf?:JSONObject.NULL).put("icr",p.recordedIcr?:JSONObject.NULL)
            .put("total",p.total24h?:JSONObject.NULL).put("estimated",p.estimatedBasal)
            .put("carbs",p.carbs24h).put("bolus",p.bolus24h).put("iob",p.iob?:JSONObject.NULL)
            .put("reason",p.unavailableReason.orEmpty()).put("responses",InsulinResponse.analyse(rows,glucose).estimates.size)
        prefs.edit().putString("snapshot",data.toString()).apply()
    }
    fun load(settings: PhoneSettings): Snapshot?=runCatching {
        val d=JSONObject(prefs.getString("snapshot",null)?:return null)
        if(d.getString("account")!=account(settings))return null
        fun number(key: String)=d.optDouble(key).takeIf {it.isFinite()}
        Snapshot(InsulinAnalytics.Point(d.getLong("time"),number("total"),d.getBoolean("estimated"),d.getDouble("carbs"),d.getDouble("bolus"),number("iob"),
            d.optString("reason").takeIf {it.isNotBlank()},number("isf"),number("icr")),d.optInt("responses"))
    }.getOrNull()
}
