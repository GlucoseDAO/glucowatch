package glucowatch.core

data class GlucoseAlertSettings(
    val enabled: Boolean = false, val lowEnabled: Boolean = true, val highEnabled: Boolean = true,
    val lowMgdl: Int = 90, val urgentLowMgdl: Int = 54, val fallMgdlPerMinute: Double = 2.0,
    val highMgdl: Int = 220, val forecastMinutes: Int = 20, val repeatMinutes: Int = 15,
) {
    init {
        require(urgentLowMgdl in 40..70 && lowMgdl in urgentLowMgdl..120)
        require(fallMgdlPerMinute.isFinite() && fallMgdlPerMinute in 0.5..5.0)
        require(highMgdl in 180..350 && forecastMinutes in 5..30 && repeatMinutes in 5..60)
    }
}

enum class GlucoseAlertKind { URGENT_LOW, FALLING_LOW, HIGH }
data class GlucoseAlert(val kind: GlucoseAlertKind, val readingTime: Long, val mgdl: Int, val predicted: Boolean) {
    val title get() = when (kind) {
        GlucoseAlertKind.URGENT_LOW -> "Urgent low glucose"
        GlucoseAlertKind.FALLING_LOW -> "Glucose falling rapidly near low"
        GlucoseAlertKind.HIGH -> if (predicted) "High glucose predicted" else "High glucose"
    }
    val message get() = when (kind) {
        GlucoseAlertKind.URGENT_LOW, GlucoseAlertKind.FALLING_LOW -> "Check glucose now and follow your hypo treatment plan. Review active insulin and pump delivery according to your care plan."
        GlucoseAlertKind.HIGH -> "Check glucose, basal rate and insulin on board (IOB). Follow your care plan before changing insulin."
    }
}

/** Only current, plausible observations can alert. Historical imports and demo data never call this. */
object GlucoseAlerts {
    fun evaluate(readings: List<GlucoseReading>, prediction: Prediction?, settings: GlucoseAlertSettings, now: Long): GlucoseAlert? {
        if (!settings.enabled) return null
        val points = readings.filter { it.mgdl in 20..600 && it.timeMillis <= now }.distinctBy { it.timeMillis }.sortedBy { it.timeMillis }
        val latest = points.lastOrNull() ?: return null
        if (now - latest.timeMillis !in 0..GlucoseHistory.FRESH_MS) return null
        val previous = points.dropLast(1).lastOrNull { latest.timeMillis - it.timeMillis in 2 * 60_000L..GlucoseHistory.GAP_MS }
        val slope = previous?.let { (latest.mgdl - it.mgdl) * 60_000.0 / (latest.timeMillis - it.timeMillis) }
        // A saved forecast is valid only when its first point is after this exact latest observation,
        // no more than ten minutes ahead. Old forecasts must not survive a new reading.
        val future = prediction?.points?.sortedBy { it.timeMillis }?.takeIf {
            it.isNotEmpty() && it.first().timeMillis - latest.timeMillis in 1..10 * 60_000L
        }?.filter { it.mgdl.isFinite() && it.timeMillis > now && it.timeMillis <= latest.timeMillis + settings.forecastMinutes * 60_000L }.orEmpty()
        val kind = when {
            settings.lowEnabled && latest.mgdl <= settings.urgentLowMgdl -> GlucoseAlertKind.URGENT_LOW
            settings.lowEnabled && latest.mgdl <= settings.lowMgdl && slope != null && slope <= -settings.fallMgdlPerMinute -> GlucoseAlertKind.FALLING_LOW
            settings.lowEnabled && latest.mgdl <= settings.lowMgdl && slope != null && slope < 0 && future.any { it.mgdl <= settings.urgentLowMgdl } -> GlucoseAlertKind.FALLING_LOW
            settings.highEnabled && latest.mgdl > settings.highMgdl -> GlucoseAlertKind.HIGH
            settings.highEnabled && slope != null && slope > 0 && future.any { it.mgdl > settings.highMgdl } -> GlucoseAlertKind.HIGH
            else -> return null
        }
        return GlucoseAlert(kind, latest.timeMillis, latest.mgdl, kind == GlucoseAlertKind.HIGH && latest.mgdl <= settings.highMgdl)
    }
}
