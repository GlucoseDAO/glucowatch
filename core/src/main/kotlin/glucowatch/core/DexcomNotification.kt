package glucowatch.core

import kotlin.math.roundToInt

/** Conservative parser for the G6 ongoing/Quick Glance notification, not its alarm thresholds. */
object DexcomNotification {
    private val packages = mapOf(
        "com.dexcom.g6" to GlucoseUnit.MGDL,
        "com.dexcom.g6.region1.mmol" to GlucoseUnit.MMOL,
        "com.dexcom.g6.region2.mgdl" to GlucoseUnit.MGDL,
        "com.dexcom.g6.region3.mgdl" to GlucoseUnit.MGDL,
        "com.dexcom.g6.region4.mmol" to GlucoseUnit.MMOL,
        "com.dexcom.g6.region5.mmol" to GlucoseUnit.MMOL,
        "com.dexcom.g6.region6.mgdl" to GlucoseUnit.MGDL,
        "com.dexcom.g6.region7.mmol" to GlucoseUnit.MMOL,
        "com.dexcom.g6.region8.mmol" to GlucoseUnit.MMOL,
        "com.dexcom.g6.region9.mgdl" to GlucoseUnit.MGDL,
        "com.dexcom.g6.region10.mgdl" to GlucoseUnit.MGDL,
        "com.dexcom.g6.region11.mmol" to GlucoseUnit.MMOL,
    )
    private val arrows = listOf("↑↑" to Trend.DoubleUp, "↓↓" to Trend.DoubleDown) +
        Trend.entries.filter { it.arrow.isNotEmpty() && it !in listOf(Trend.NotComputable, Trend.RateOutOfRange) }
            .map { it.arrow to it }
    private val valuePattern = Regex("""^(?:glucose\s*:?\s*)?(\d{1,3}(?:[.,]\d)?)\s*(mg\s*/\s*dl|mmol\s*/\s*l)?$""", RegexOption.IGNORE_CASE)
    private val unavailable = Regex("""\b(signal loss|no readings|sensor error|sensor failed|warm.?up|brief sensor issue|urgent low soon|LOW|HIGH)\b""", RegexOption.IGNORE_CASE)

    fun supports(packageName: String) = packageName in packages

    /** Each field is a whole text view or notification extra. Never search arbitrary prose for a number. */
    fun parse(packageName: String, fields: List<String>, postedAt: Long, now: Long): GlucoseReading? {
        val defaultUnit = packages[packageName] ?: return null
        if (postedAt <= 0 || postedAt > now || now - postedAt > MAX_AGE_MS) return null
        val texts = fields.map { it.replace('\u00a0', ' ').replace("\u2060", "").trim() }.filter { it.isNotEmpty() }
        if (texts.any { unavailable.containsMatchIn(it) }) return null
        val explicitUnits = texts.mapNotNull { text ->
            when {
                Regex("mg\\s*/\\s*dl", RegexOption.IGNORE_CASE).containsMatchIn(text) -> GlucoseUnit.MGDL
                Regex("mmol\\s*/\\s*l", RegexOption.IGNORE_CASE).containsMatchIn(text) -> GlucoseUnit.MMOL
                else -> null
            }
        }.distinct()
        if (explicitUnits.size > 1) return null
        val unit = explicitUnits.singleOrNull() ?: defaultUnit
        val candidates = texts.mapNotNull { text ->
            val withoutArrows = arrows.fold(text) { s, (arrow, _) -> s.replace(arrow, "") }.trim()
            val match = valuePattern.matchEntire(withoutArrows) ?: return@mapNotNull null
            val raw = match.groupValues[1]
            // A bare integer in an mmol layout may be a time, countdown or chart tick.
            if (unit == GlucoseUnit.MMOL && '.' !in raw && ',' !in raw) return@mapNotNull null
            if (unit == GlucoseUnit.MGDL && ('.' in raw || ',' in raw)) return@mapNotNull null
            val number = raw.replace(',', '.').toDouble()
            val mgdl = (if (unit == GlucoseUnit.MMOL) number * GlucoseUnit.MGDL_PER_MMOL else number).roundToInt()
            mgdl.takeIf { it in 40..400 }
        }.distinct()
        if (candidates.size != 1) return null
        val trends = texts.mapNotNull { text -> arrows.firstOrNull { (arrow, _) -> arrow in text }?.second }.distinct()
        return GlucoseReading(postedAt, candidates.single(), trends.singleOrNull() ?: Trend.None)
    }

    /** Reject redelivery and rapid notification repainting, but retain equal glucose on later samples. */
    fun isNew(reading: GlucoseReading, previous: GlucoseReading?) =
        previous == null || reading.timeMillis - previous.timeMillis >= 60_000L

    const val MAX_AGE_MS = 10 * 60_000L
}
