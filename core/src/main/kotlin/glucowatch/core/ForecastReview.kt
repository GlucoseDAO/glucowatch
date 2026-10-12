package glucowatch.core

import kotlin.math.abs

/** One forecast point and the reading that landed nearest it, when one did. */
data class ForecastSample(
    val timeMillis: Long,
    val predictedMgdl: Double,
    val actualMgdl: Int?,
)

/** A stored forecast laid against the readings that followed it. */
data class ForecastReview(
    val modelId: String,
    val status: String,
    val samples: List<ForecastSample>,
)

/** Whether a stored forecast ran to its last point or stopped because readings were missing. */
object ForecastReviewer {
    fun review(check: PredictionCheck, readings: List<GlucoseReading>, now: Long): ForecastReview {
        val points = check.prediction.points.sortedBy { it.timeMillis }
        val end = points.lastOrNull()?.timeMillis ?: check.savedAt
        val samples = points.map { point ->
            val near = readings.minByOrNull { abs(it.timeMillis - point.timeMillis) }
                ?.takeIf { abs(it.timeMillis - point.timeMillis) <= GlucoseHistory.GAP_MS }
            ForecastSample(point.timeMillis, point.mgdl, near?.mgdl)
        }
        val dueUntil = minOf(now, end)
        val arrived = readings.filter { it.timeMillis in check.savedAt..dueUntil }
        val first = arrived.minOfOrNull { it.timeMillis }
        val missedStart = first != null && first - check.savedAt > GlucoseHistory.GAP_MS &&
            now - check.savedAt > GlucoseHistory.GAP_MS
        val missing = missedStart || (arrived.isNotEmpty() && GlucoseHistory.gaps(arrived, dueUntil).isNotEmpty())
        val status = when {
            points.isEmpty() -> "No forecast"
            arrived.isEmpty() && now < end -> "Waiting for readings"
            arrived.isEmpty() -> "Interrupted · readings never arrived"
            missing -> "Interrupted · missing readings"
            now < end -> "In progress"
            else -> "Finished"
        }
        return ForecastReview(check.prediction.modelId, status, samples)
    }

    /** Names stored with a forecast, including older checks that only kept the model id. */
    fun title(modelId: String): String = when (modelId) {
        "linear" -> "Linear trend"
        "onnx" -> "Imported model"
        "phone" -> "Phone app model"
        "loop" -> "Loop forecast"
        else -> modelId
    }
}
