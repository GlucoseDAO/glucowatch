package glucowatch.core

import kotlin.math.sqrt

/** A forecast point; [lower]/[upper] are an optional uncertainty band. All values in mg/dL. */
data class PredictedPoint(
    val timeMillis: Long,
    val mgdl: Double,
    val lower: Double? = null,
    val upper: Double? = null,
)

data class Prediction(val modelId: String, val points: List<PredictedPoint>)

/**
 * Plug your own model in by implementing this interface and adding it to [Predictors.all].
 * [history] is sorted oldest first and covers up to the last 24 hours.
 * Return null when the model cannot make a forecast (too little data, gaps, ...).
 */
interface GlucosePredictor {
    val id: String
    val displayName: String
    fun predict(history: List<GlucoseReading>, horizonMinutes: Int): Prediction?
}

/** Registry of available models. The watch app lets the user pick one by [GlucosePredictor.id]. */
object Predictors {
    val all: List<GlucosePredictor> = listOf(LinearTrendPredictor())

    fun byId(id: String): GlucosePredictor = all.firstOrNull { it.id == id } ?: all.first()
}

/**
 * Baseline example model: least-squares line through the last [windowMinutes] of readings,
 * extrapolated with an uncertainty band that widens with the square root of time.
 */
class LinearTrendPredictor(
    private val windowMinutes: Int = 20,
    private val stepMinutes: Int = 5,
) : GlucosePredictor {
    override val id = ID
    override val displayName = "Linear trend"

    override fun predict(history: List<GlucoseReading>, horizonMinutes: Int): Prediction? {
        val last = history.lastOrNull() ?: return null
        val window = history.filter { last.timeMillis - it.timeMillis <= windowMinutes * 60_000L }
        if (window.size < 3) return null

        val xs = window.map { (it.timeMillis - last.timeMillis) / 60_000.0 }
        val ys = window.map { it.mgdl.toDouble() }
        val mx = xs.average()
        val my = ys.average()
        val sxx = xs.sumOf { (it - mx) * (it - mx) }
        if (sxx == 0.0) return null
        val slope = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / sxx
        val intercept = my - slope * mx
        val residual = sqrt(xs.indices.sumOf { val e = ys[it] - (intercept + slope * xs[it]); e * e } / window.size)

        val points = (stepMinutes..horizonMinutes step stepMinutes).map { m ->
            val y = (intercept + slope * m).coerceIn(40.0, 400.0)
            val spread = residual + 2.5 * sqrt(m.toDouble())
            PredictedPoint(last.timeMillis + m * 60_000L, y, (y - spread).coerceAtLeast(40.0), (y + spread).coerceAtMost(400.0))
        }
        return Prediction(id, points)
    }

    companion object {
        const val ID = "linear"
    }
}
