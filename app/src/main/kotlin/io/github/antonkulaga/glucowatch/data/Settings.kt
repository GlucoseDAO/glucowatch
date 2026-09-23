package io.github.antonkulaga.glucowatch.data

import android.content.Context
import glucowatch.core.GlucoseUnit
import glucowatch.core.LinearTrendPredictor
import glucowatch.core.Region

enum class DataSource { DEMO, SHARE }

data class Settings(
    val source: DataSource = DataSource.DEMO,
    val username: String = "",
    val password: String = "",
    val region: Region = Region.OUS,
    val unit: GlucoseUnit = GlucoseUnit.MMOL,
    val lowMgdl: Int = 70,
    val highMgdl: Int = 180,
    val chartHours: Int = 3,
    val predictionEnabled: Boolean = false,
    val predictorId: String = LinearTrendPredictor.ID,
    val horizonMinutes: Int = 30,
) {
    val hasCredentials get() = username.isNotBlank() && password.isNotBlank()
}

/**
 * Settings live only on the watch, in app-private storage (never backed up or sent anywhere
 * except to the Dexcom server itself).
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
        if (new.username != old.username || new.region != old.region) GlucoseRepository(appContext).clearCache()
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
            unit = enumOr(prefs.getString("unit", null), d.unit),
            lowMgdl = prefs.getInt("low", d.lowMgdl),
            highMgdl = prefs.getInt("high", d.highMgdl),
            chartHours = prefs.getInt("chartHours", d.chartHours),
            predictionEnabled = prefs.getBoolean("prediction", d.predictionEnabled),
            predictorId = prefs.getString("predictor", d.predictorId)!!,
            horizonMinutes = prefs.getInt("horizon", d.horizonMinutes),
        )
    }

    fun save(s: Settings) {
        prefs.edit()
            .putString("source", s.source.name)
            .putString("username", s.username)
            .putString("password", s.password)
            .putString("region", s.region.name)
            .putString("unit", s.unit.name)
            .putInt("low", s.lowMgdl)
            .putInt("high", s.highMgdl)
            .putInt("chartHours", s.chartHours)
            .putBoolean("prediction", s.predictionEnabled)
            .putString("predictor", s.predictorId)
            .putInt("horizon", s.horizonMinutes)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default
}
