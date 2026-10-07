package glucowatch.core

import java.io.File

/** A forecast saved at [savedAt], kept so it can be laid over the readings that followed. */
data class PredictionCheck(val savedAt: Long, val prediction: Prediction)

/**
 * A short log of forecasts the user asked to keep. One line is `savedAt` and
 * [CacheFormat.encodePrediction], tab separated. The file holds the newest [MAX_CHECKS].
 */
object PredictionChecks {
    const val FILE_NAME = "prediction-checks.txt"
    const val MAX_CHECKS = 40

    fun load(file: File): List<PredictionCheck> {
        if (!file.isFile) return emptyList()
        return file.readLines().mapNotNull(::decode)
    }

    /** Appends [prediction]. A forecast with no points is not stored. */
    fun record(file: File, prediction: Prediction, now: Long = System.currentTimeMillis()): PredictionCheck? {
        if (prediction.points.isEmpty()) return null
        val kept = (load(file) + PredictionCheck(now, prediction)).takeLast(MAX_CHECKS)
        file.parentFile?.mkdirs()
        file.writeText(kept.joinToString("\n") { encode(it) } + "\n")
        return kept.last()
    }

    fun encode(check: PredictionCheck): String =
        "${check.savedAt}\t${CacheFormat.encodePrediction(check.prediction)}"

    fun decode(line: String): PredictionCheck? {
        val savedAt = line.substringBefore('\t').toLongOrNull() ?: return null
        val prediction = CacheFormat.decodePrediction(line.substringAfter('\t')) ?: return null
        if (prediction.points.isEmpty()) return null
        return PredictionCheck(savedAt, prediction)
    }
}
