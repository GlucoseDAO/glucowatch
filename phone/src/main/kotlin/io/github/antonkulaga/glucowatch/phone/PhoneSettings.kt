package io.github.antonkulaga.glucowatch.phone

import android.content.Context
import glucowatch.core.GlucoseUnit
import glucowatch.core.CareLinkLogin
import glucowatch.core.LinearTrendPredictor
import glucowatch.core.NightscoutApi
import glucowatch.core.Region
import glucowatch.core.SourceAccount
import glucowatch.core.link.LinkAccount
import glucowatch.core.link.LinkSource

/** The phone's own source and the model it forecasts with for the watch. */
data class PhoneSettings(
    val source: LinkSource = runCatching { LinkSource.valueOf(BuildConfig.DEV_GLUCOWATCH_SOURCE.uppercase()) }.getOrDefault(LinkSource.DEMO),
    val username: String = BuildConfig.DEV_DEXCOM_USERNAME,
    val password: String = BuildConfig.DEV_DEXCOM_PASSWORD,
    val region: Region = runCatching { Region.parse(BuildConfig.DEV_DEXCOM_REGION) }.getOrDefault(Region.OUS),
    val nightscoutUrl: String = BuildConfig.DEV_NIGHTSCOUT_URL,
    val nightscoutToken: String = BuildConfig.DEV_NIGHTSCOUT_TOKEN,
    val nightscoutApi: NightscoutApi = runCatching { NightscoutApi.parse(BuildConfig.DEV_NIGHTSCOUT_API) }.getOrDefault(NightscoutApi.V1),
    val unit: GlucoseUnit = runCatching { GlucoseUnit.parse(BuildConfig.DEV_GLUCOWATCH_UNIT) }.getOrDefault(GlucoseUnit.MMOL),
    val predictorId: String = LinearTrendPredictor.ID,
    /** Draw heart rate as a second track on the glucose chart. The current bpm shows either way. */
    val heartTrack: Boolean = false,
    val carelinkAccount: String = "",
    val alsoFrom: Set<LinkSource> = emptySet(),
) {
    val extras get() = if (source == LinkSource.DEMO) emptyList() else THERAPY_SOURCES.filter { it in alsoFrom && it != source }

    fun account(of: LinkSource, carelink: CareLinkLogin): SourceAccount? = when (of) {
        LinkSource.SHARE -> SourceAccount.Share(region, username, password)
        LinkSource.NIGHTSCOUT -> SourceAccount.Nightscout(nightscoutUrl, nightscoutToken, nightscoutApi)
        LinkSource.DEMO -> null
        LinkSource.CARELINK -> SourceAccount.CareLink(carelink)
    }

    /** Changes when readings would come from another account or server, as on the watch. */
    fun keyOf(of: LinkSource): String = when (of) {
        LinkSource.DEMO -> "demo"
        LinkSource.SHARE -> "share:$region:$username"
        LinkSource.NIGHTSCOUT -> "nightscout:${nightscoutUrl.trim().trimEnd('/').lowercase()}:$nightscoutApi"
        LinkSource.CARELINK -> "carelink:$carelinkAccount"
    }

    val accountKey get() = keyOf(source)

    /** Includes pump selections: changing one must invalidate the watch's relayed history too. */
    val configurationKey get() = (listOf(accountKey) + extras.map(::keyOf)).joinToString("|")

    val sourceLabel get() = label(source) + extras.joinToString("") { " + ${label(it)} insulin" }

    fun label(of: LinkSource) = when (of) {
        LinkSource.DEMO -> "Demo data"
        LinkSource.SHARE -> "Dexcom Share"
        LinkSource.NIGHTSCOUT -> "Nightscout"
        LinkSource.CARELINK -> "CareLink (MiniMed)"
    }

    fun toLink() = LinkAccount(source, username, password, region, nightscoutUrl, nightscoutToken, nightscoutApi)

    companion object {
        val THERAPY_SOURCES = listOf(LinkSource.NIGHTSCOUT, LinkSource.CARELINK)
    }
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
            heartTrack = prefs.getBoolean("heartTrack", d.heartTrack),
            carelinkAccount = prefs.getString("carelinkAccount", "").orEmpty(),
            alsoFrom = prefs.getString("alsoFrom", "").orEmpty().split(',')
                .mapNotNull { runCatching { LinkSource.valueOf(it) }.getOrNull() }.toSet(),
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
            .putBoolean("heartTrack", s.heartTrack)
            .putString("carelinkAccount", s.carelinkAccount)
            .putString("alsoFrom", s.alsoFrom.joinToString(",") { it.name })
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default
}
