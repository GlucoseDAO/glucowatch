package io.github.antonkulaga.glucowatch.phone

import android.content.Context
import glucowatch.core.GlucoseUnit
import glucowatch.core.LinearTrendPredictor
import glucowatch.core.NightscoutApi
import glucowatch.core.Region
import glucowatch.core.SourceAccount
import glucowatch.core.link.LinkAccount
import glucowatch.core.link.LinkSource

/** The phone's own source and the model it forecasts with for the watch. */
data class PhoneSettings(
    val source: LinkSource = LinkSource.DEMO,
    val username: String = "",
    val password: String = "",
    val region: Region = Region.OUS,
    val nightscoutUrl: String = "",
    val nightscoutToken: String = "",
    val nightscoutApi: NightscoutApi = NightscoutApi.V1,
    val unit: GlucoseUnit = GlucoseUnit.MMOL,
    val predictorId: String = LinearTrendPredictor.ID,
) {
    val account: SourceAccount? get() = when (source) {
        LinkSource.SHARE -> SourceAccount.Share(region, username, password)
        LinkSource.NIGHTSCOUT -> SourceAccount.Nightscout(nightscoutUrl, nightscoutToken, nightscoutApi)
        LinkSource.DEMO -> null
    }

    /** Changes when readings would come from another account or server, as on the watch. */
    val accountKey get() = when (source) {
        LinkSource.DEMO -> "demo"
        LinkSource.SHARE -> "share:$region:$username"
        LinkSource.NIGHTSCOUT -> "nightscout:${nightscoutUrl.trim().trimEnd('/').lowercase()}:$nightscoutApi"
    }

    val sourceLabel get() = when (source) {
        LinkSource.DEMO -> "Demo data"
        LinkSource.SHARE -> "Dexcom Share"
        LinkSource.NIGHTSCOUT -> "Nightscout"
    }

    fun toLink() = LinkAccount(source, username, password, region, nightscoutUrl, nightscoutToken, nightscoutApi)
}

/** App-private storage on the phone. The login leaves it only for Dexcom, the user's Nightscout, or a paired watch. */
class PhoneSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): PhoneSettings {
        val d = PhoneSettings()
        return PhoneSettings(
            source = enumOr(prefs.getString("source", null), d.source),
            username = prefs.getString("username", d.username)!!,
            password = prefs.getString("password", d.password)!!,
            region = enumOr(prefs.getString("region", null), d.region),
            nightscoutUrl = prefs.getString("nightscoutUrl", d.nightscoutUrl)!!,
            nightscoutToken = prefs.getString("nightscoutToken", d.nightscoutToken)!!,
            nightscoutApi = enumOr(prefs.getString("nightscoutApi", null), d.nightscoutApi),
            unit = enumOr(prefs.getString("unit", null), d.unit),
            predictorId = prefs.getString("predictor", d.predictorId)!!,
        )
    }

    fun save(s: PhoneSettings) {
        prefs.edit()
            .putString("source", s.source.name)
            .putString("username", s.username)
            .putString("password", s.password)
            .putString("region", s.region.name)
            .putString("nightscoutUrl", s.nightscoutUrl)
            .putString("nightscoutToken", s.nightscoutToken)
            .putString("nightscoutApi", s.nightscoutApi.name)
            .putString("unit", s.unit.name)
            .putString("predictor", s.predictorId)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default
}
