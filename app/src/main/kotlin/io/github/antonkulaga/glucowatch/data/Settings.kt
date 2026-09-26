package io.github.antonkulaga.glucowatch.data

import android.content.Context
import glucowatch.core.CareLinkLogin
import glucowatch.core.CareLinkToken
import glucowatch.core.DohResolver
import glucowatch.core.GlucoseUnit
import glucowatch.core.LinearTrendPredictor
import glucowatch.core.NightscoutApi
import glucowatch.core.Region
import glucowatch.core.SourceAccount

enum class DataSource(val label: String) {
    DEMO("Demo data"),
    SHARE("Dexcom Share"),
    NIGHTSCOUT("Nightscout"),
    CARELINK("Medtronic CareLink"),
    PHONE("Phone app"),
}

enum class StaleAlert(val label: String) {
    OFF("Off"),
    VIBRATE("Vibrate"),
    SOUND("Sound and vibrate"),
}

data class Settings(
    val source: DataSource = DataSource.DEMO,
    val username: String = "",
    val password: String = "",
    val region: Region = Region.OUS,
    /** Optional HTTP CONNECT proxy for the watch's Share fallback; empty means direct network only. */
    val shareProxy: String = "",
    /** Resolve Dexcom over HTTPS when the network's own DNS will not answer for it. */
    val shareDoh: Boolean = false,
    /** The DoH endpoint, an IP literal so it needs no working DNS of its own. */
    val dohEndpoint: String = DohResolver.CLOUDFLARE,
    val nightscoutUrl: String = "",
    val nightscoutToken: String = "",
    val nightscoutApi: NightscoutApi = NightscoutApi.V1,
    /** The signed-in CareLink account (the token's subject); the tokens live in [CareLinkTokenStore]. */
    val carelinkAccount: String = "",
    /**
     * Sources that add insulin, carbs and active insulin to [source]'s readings, for a pump on
     * CareLink next to a Dexcom sensor. Only [THERAPY_SOURCES] count; see [extras].
     */
    val alsoFrom: Set<DataSource> = emptySet(),
    val unit: GlucoseUnit = GlucoseUnit.MMOL,
    val lowMgdl: Int = 70,
    val highMgdl: Int = 180,
    val chartHours: Int = 3,
    val predictionEnabled: Boolean = false,
    val predictorId: String = LinearTrendPredictor.ID,
    val horizonMinutes: Int = 30,
    val staleAlert: StaleAlert = StaleAlert.VIBRATE,
    /** Hex id of the paired phone app (see PhonePairingStore); empty before pairing. */
    val phoneId: String = "",
) {
    val hasCredentials get() = username.isNotBlank() && password.isNotBlank()

    /** The extra sources in use: in [THERAPY_SOURCES] and not the main [source] itself. */
    val extras: List<DataSource> get() = THERAPY_SOURCES.filter { it in alsoFrom && it != source }

    /** The login the watch fetches [of] with itself; null for demo data and for the phone app. */
    fun account(of: DataSource, carelink: CareLinkLogin): SourceAccount? = when (of) {
        DataSource.SHARE -> SourceAccount.Share(region, username, password)
        DataSource.NIGHTSCOUT -> SourceAccount.Nightscout(nightscoutUrl, nightscoutToken, nightscoutApi)
        DataSource.CARELINK -> SourceAccount.CareLink(carelink)
        DataSource.DEMO, DataSource.PHONE -> null
    }

    /** Changes when data of [of] would come from another account or server, so its cache must go. */
    fun keyOf(of: DataSource) = when (of) {
        DataSource.DEMO -> "demo"
        DataSource.SHARE -> "share:$region:$username"
        DataSource.NIGHTSCOUT -> "nightscout:${nightscoutUrl.trim().trimEnd('/').lowercase()}:$nightscoutApi"
        DataSource.CARELINK -> "carelink:$carelinkAccount"
        DataSource.PHONE -> "phone:$phoneId"
    }

    /** The main source's [keyOf]: readings of another account never serve as history for this one. */
    val accountKey get() = keyOf(source)

    companion object {
        /** Sources that can add insulin and carbs to another source's readings. */
        val THERAPY_SOURCES = listOf(DataSource.NIGHTSCOUT, DataSource.CARELINK)
    }
}

/**
 * The CareLink sign-in, kept apart from [Settings] because every token refresh rewrites it. It
 * arrives from the phone app over the paired link (or, in debug builds, from adb) and never leaves
 * the watch except to CareLink.
 */
class CareLinkTokenStore(context: Context) : CareLinkLogin {
    private val prefs = context.applicationContext.getSharedPreferences("carelink", Context.MODE_PRIVATE)

    override fun load(): CareLinkToken? = CareLinkToken.decode(prefs.getString(TOKEN, null))

    override fun replace(old: CareLinkToken, new: CareLinkToken) = synchronized(lock) {
        if (load()?.refreshToken == old.refreshToken) prefs.edit().putString(TOKEN, new.encode()).commit()
        Unit
    }

    fun save(token: CareLinkToken?) = synchronized(lock) {
        prefs.edit().putString(TOKEN, token?.encode()).commit()
        Unit
    }

    private companion object {
        const val TOKEN = "token"
        val lock = Any()
    }
}

/**
 * Settings live only on the watch, in app-private storage (never backed up or sent anywhere
 * except to the Dexcom server or the user's own Nightscout). A login copied from the phone app
 * arrives encrypted over the paired Bluetooth link, see docs/phone-link.md.
 */
class SettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): Settings {
        applyBuildDefaults()
        return read()
    }

    /**
     * Debug builds: apply `.env` values once per distinct set of values. Later edits in Settings
     * are kept until `.env` changes (or the app data is cleared).
     */
    private fun applyBuildDefaults() {
        if (!BuildDefaults.present || prefs.getString("buildDefaults", null) == BuildDefaults.fingerprint) return
        val old = read()
        val new = BuildDefaults.applyTo(old)
        // Another account or server: cached readings and session belong to the old one.
        if (new.accountKey != old.accountKey) GlucoseRepository(appContext).clearCache()
        save(new)
        prefs.edit().putString("buildDefaults", BuildDefaults.fingerprint).apply()
    }

    private fun read(): Settings {
        val d = Settings()
        return Settings(
            source = enumOr(prefs.getString("source", null), d.source),
            username = prefs.getString("username", d.username)!!,
            password = prefs.getString("password", d.password)!!,
            region = enumOr(prefs.getString("region", null), d.region),
            shareProxy = prefs.getString("shareProxy", d.shareProxy)!!,
            shareDoh = prefs.getBoolean("shareDoh", d.shareDoh),
            dohEndpoint = prefs.getString("dohEndpoint", d.dohEndpoint)!!,
            nightscoutUrl = prefs.getString("nightscoutUrl", d.nightscoutUrl)!!,
            nightscoutToken = prefs.getString("nightscoutToken", d.nightscoutToken)!!,
            nightscoutApi = enumOr(prefs.getString("nightscoutApi", null), d.nightscoutApi),
            carelinkAccount = prefs.getString("carelinkAccount", d.carelinkAccount)!!,
            alsoFrom = prefs.getString("alsoFrom", "")!!.split(',').mapNotNull { enumOrNull<DataSource>(it) }.toSet(),
            unit = enumOr(prefs.getString("unit", null), d.unit),
            lowMgdl = prefs.getInt("low", d.lowMgdl),
            highMgdl = prefs.getInt("high", d.highMgdl),
            chartHours = prefs.getInt("chartHours", d.chartHours),
            predictionEnabled = prefs.getBoolean("prediction", d.predictionEnabled),
            predictorId = prefs.getString("predictor", d.predictorId)!!,
            horizonMinutes = prefs.getInt("horizon", d.horizonMinutes),
            staleAlert = enumOr(prefs.getString("staleAlert", null), d.staleAlert),
            phoneId = prefs.getString("phoneId", d.phoneId)!!,
        )
    }

    fun save(s: Settings) {
        prefs.edit()
            .putString("source", s.source.name)
            .putString("username", s.username)
            .putString("password", s.password)
            .putString("region", s.region.name)
            .putString("shareProxy", s.shareProxy)
            .putBoolean("shareDoh", s.shareDoh)
            .putString("dohEndpoint", s.dohEndpoint)
            .putString("nightscoutUrl", s.nightscoutUrl)
            .putString("nightscoutToken", s.nightscoutToken)
            .putString("nightscoutApi", s.nightscoutApi.name)
            .putString("carelinkAccount", s.carelinkAccount)
            .putString("alsoFrom", s.alsoFrom.joinToString(",") { it.name })
            .putString("unit", s.unit.name)
            .putInt("low", s.lowMgdl)
            .putInt("high", s.highMgdl)
            .putInt("chartHours", s.chartHours)
            .putBoolean("prediction", s.predictionEnabled)
            .putString("predictor", s.predictorId)
            .putInt("horizon", s.horizonMinutes)
            .putString("staleAlert", s.staleAlert.name)
            .putString("phoneId", s.phoneId)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E = enumOrNull<E>(name) ?: default

    private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? =
        name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() }
}
