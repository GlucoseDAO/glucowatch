package io.github.antonkulaga.glucowatch.data

import android.content.Context
import android.util.Log
import glucowatch.core.DemoData
import glucowatch.core.DexcomShareClient
import glucowatch.core.GlucoseReading
import glucowatch.core.Prediction
import glucowatch.core.Predictors
import glucowatch.core.Trend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Snapshot of what the UI and complications show. */
data class GlucoseState(
    val settings: Settings,
    val readings: List<GlucoseReading>,
    val prediction: Prediction?,
    val lastError: String?,
    val lastFetchMillis: Long,
) {
    val latest get() = readings.lastOrNull()

    fun ageMinutes(now: Long = System.currentTimeMillis()): Long? = latest?.let { (now - it.timeMillis) / 60_000 }

    fun isStale(now: Long = System.currentTimeMillis()) = (ageMinutes(now) ?: Long.MAX_VALUE) > STALE_MINUTES

    companion object {
        const val STALE_MINUTES = 12
    }
}

/** Fetches readings (Share or demo), keeps a 24 h cache on disk and computes the optional forecast. */
class GlucoseRepository(context: Context) {
    private val settingsStore = SettingsStore(context)
    private val cache = context.applicationContext.getSharedPreferences("cache", Context.MODE_PRIVATE)

    fun state(): GlucoseState {
        val settings = settingsStore.load()
        val readings = if (settings.source == DataSource.DEMO) {
            DemoData.readings(System.currentTimeMillis())
        } else {
            decode(cache.getString("readings", "") ?: "")
        }
        return GlucoseState(
            settings = settings,
            readings = readings,
            prediction = predict(settings, readings),
            lastError = cache.getString("error", null),
            lastFetchMillis = cache.getLong("fetchedAt", 0),
        )
    }

    /** Downloads new readings. Never throws: errors are stored and shown in the app. */
    suspend fun refresh(): GlucoseState = lock.withLock {
        withContext(Dispatchers.IO) {
            val settings = settingsStore.load()
            if (settings.source == DataSource.SHARE) {
                if (!settings.hasCredentials) {
                    saveError("Enter Dexcom username and password in settings")
                } else {
                    runCatching { fetchShare(settings) }
                        .onSuccess { saveError(null) }
                        .onFailure {
                            Log.w(TAG, "Share refresh failed", it)
                            saveError(it.message ?: it.javaClass.simpleName)
                        }
                }
            }
            cache.edit().putLong("fetchedAt", System.currentTimeMillis()).apply()
            state()
        }
    }

    fun clearCache() {
        cache.edit().clear().apply()
    }

    private fun fetchShare(settings: Settings) {
        val old = decode(cache.getString("readings", "") ?: "")
        val now = System.currentTimeMillis()
        val since = old.lastOrNull()?.timeMillis
        // Full day on first run, afterwards only what is new (plus a margin for late uploads).
        val minutes = if (since == null) DexcomShareClient.MAX_MINUTES
        else (((now - since) / 60_000) + 15).toInt().coerceIn(15, DexcomShareClient.MAX_MINUTES)
        val maxCount = (minutes / 5 + 2).coerceAtMost(DexcomShareClient.MAX_COUNT)

        val client = DexcomShareClient(
            region = settings.region,
            username = settings.username,
            password = settings.password,
            sessionId = cache.getString("session:${settings.region}:${settings.username}", null),
        )
        val fresh = client.readings(minutes, maxCount)
        val merged = (old + fresh)
            .associateBy { it.timeMillis }
            .values
            .filter { now - it.timeMillis <= 24 * 3_600_000L }
            .sortedBy { it.timeMillis }
        cache.edit()
            .putString("readings", encode(merged))
            .putString("session:${settings.region}:${settings.username}", client.sessionId)
            .apply()
    }

    private fun predict(settings: Settings, readings: List<GlucoseReading>): Prediction? {
        if (!settings.predictionEnabled || readings.isEmpty()) return null
        return runCatching { Predictors.byId(settings.predictorId).predict(readings, settings.horizonMinutes) }
            .onFailure { Log.w(TAG, "Predictor ${settings.predictorId} failed", it) }
            .getOrNull()
    }

    private fun saveError(message: String?) {
        cache.edit().putString("error", message).apply()
    }

    private fun encode(list: List<GlucoseReading>) =
        list.joinToString(";") { "${it.timeMillis},${it.mgdl},${it.trend.name}" }

    private fun decode(text: String): List<GlucoseReading> =
        text.split(';').mapNotNull { row ->
            val p = row.split(',')
            if (p.size != 3) return@mapNotNull null
            GlucoseReading(p[0].toLongOrNull() ?: return@mapNotNull null, p[1].toIntOrNull() ?: return@mapNotNull null, Trend.parse(p[2]))
        }

    companion object {
        private const val TAG = "GlucoRepo"
        private val lock = Mutex()
    }
}
