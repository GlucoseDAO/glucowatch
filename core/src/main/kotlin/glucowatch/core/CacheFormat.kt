package glucowatch.core

/**
 * Compact text form of the cached data, for the watch's SharedPreferences.
 * Rows that do not parse are skipped, so an old or damaged cache just starts empty.
 */
object CacheFormat {
    fun encodeReadings(list: List<GlucoseReading>): String =
        list.joinToString(";") { "${it.timeMillis},${it.mgdl},${it.trend.name}" }

    fun decodeReadings(text: String): List<GlucoseReading> =
        text.split(';').mapNotNull { row ->
            val p = row.split(',')
            if (p.size != 3) return@mapNotNull null
            GlucoseReading(p[0].toLongOrNull() ?: return@mapNotNull null, p[1].toIntOrNull() ?: return@mapNotNull null, Trend.parse(p[2]))
        }

    fun encodeTreatments(list: List<Treatment>): String =
        list.joinToString(";") { "${it.timeMillis},${it.insulin},${it.carbs},${if (it.automatic) 1 else 0}" }

    fun decodeTreatments(text: String): List<Treatment> =
        text.split(';').mapNotNull { row ->
            val p = row.split(',')
            if (p.size != 4) return@mapNotNull null
            Treatment(
                timeMillis = p[0].toLongOrNull() ?: return@mapNotNull null,
                insulin = p[1].toDoubleOrNull() ?: return@mapNotNull null,
                carbs = p[2].toDoubleOrNull() ?: return@mapNotNull null,
                automatic = p[3] == "1",
            )
        }

    /** `time,iob,cob,eventual,name|t:v;t:v…` with empty fields for missing values. */
    fun encodeLoop(status: LoopStatus?): String {
        if (status == null) return ""
        val head = listOf(status.timeMillis, status.iob, status.cob, status.eventualMgdl, status.forecastName)
            .joinToString(",") { it?.toString().orEmpty() }
        return head + "|" + status.forecast.joinToString(";") { "${it.timeMillis}:${it.mgdl}" }
    }

    /** `model|t:v:lower:upper;…`, with empty bounds when the model gives none. */
    fun encodePrediction(prediction: Prediction?): String {
        if (prediction == null) return ""
        return prediction.modelId + "|" + prediction.points.joinToString(";") {
            "${it.timeMillis}:${it.mgdl}:${it.lower?.toString().orEmpty()}:${it.upper?.toString().orEmpty()}"
        }
    }

    fun decodePrediction(text: String): Prediction? {
        if ('|' !in text) return null
        val points = text.substringAfter('|').split(';').mapNotNull { point ->
            val p = point.split(':')
            if (p.size != 4) return@mapNotNull null
            PredictedPoint(
                timeMillis = p[0].toLongOrNull() ?: return@mapNotNull null,
                mgdl = p[1].toDoubleOrNull() ?: return@mapNotNull null,
                lower = p[2].toDoubleOrNull(),
                upper = p[3].toDoubleOrNull(),
            )
        }
        return Prediction(text.substringBefore('|'), points)
    }

    fun decodeLoop(text: String): LoopStatus? {
        val head = text.substringBefore('|').split(',')
        if (head.size != 5) return null
        val forecast = text.substringAfter('|', "").split(';').mapNotNull { point ->
            val t = point.substringBefore(':').toLongOrNull() ?: return@mapNotNull null
            val v = point.substringAfter(':').toDoubleOrNull() ?: return@mapNotNull null
            PredictedPoint(t, v)
        }
        return LoopStatus(
            timeMillis = head[0].toLongOrNull() ?: return null,
            iob = head[1].toDoubleOrNull(),
            cob = head[2].toDoubleOrNull(),
            eventualMgdl = head[3].toDoubleOrNull(),
            forecast = forecast,
            forecastName = head[4].ifEmpty { null },
        )
    }
}
