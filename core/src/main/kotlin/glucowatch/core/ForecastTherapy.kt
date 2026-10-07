package glucowatch.core

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.*

/** A successfully read treatment log: no bolus/meal event within it means a recorded zero. */
data class TherapyRange(val start: Long, val end: Long)
data class BasalEntry(val seconds: Int, val unitsPerHour: Float)
data class BasalProfile(val effective: Long, val zone: String, val entries: List<BasalEntry>) {
    fun rate(at: Long): Float {
        val clock = Instant.ofEpochMilli(at).atZone(ZoneId.of(zone)).toLocalTime().toSecondOfDay()
        return entries.lastOrNull { it.seconds <= clock }?.unitsPerHour ?: entries.last().unitsPerHour
    }
    fun boundaries(start: Long, end: Long): List<Long> {
        val zoneId = ZoneId.of(zone)
        val day = Instant.ofEpochMilli(start).atZone(zoneId).toLocalDate()
        return (0L..1L).flatMap { offset -> entries.map { entry ->
            day.plusDays(offset).atTime(java.time.LocalTime.ofSecondOfDay(entry.seconds.toLong())).atZone(zoneId).toInstant().toEpochMilli()
        } }.filter { it in (start + 1) until end }
    }
}

data class ForecastTherapy(val ranges: List<TherapyRange> = emptyList(), val profiles: List<BasalProfile> = emptyList()) {
    companion object {
        fun profiles(json: String): List<BasalProfile> = runCatching {
            Json.parseToJsonElement(json).jsonArray.mapNotNull { document -> runCatching {
                val root = document.jsonObject
                val effective = root["mills"]?.jsonPrimitive?.longOrNull ?: Instant.parse(root.getValue("startDate").jsonPrimitive.content).toEpochMilli()
                val store = root.getValue("store").jsonObject
                val profile = store.getValue(root.getValue("defaultProfile").jsonPrimitive.content).jsonObject
                val zone = profile.getValue("timezone").jsonPrimitive.content
                ZoneId.of(zone)
                val entries = profile.getValue("basal").jsonArray.map { point ->
                    val p = point.jsonObject
                    val seconds = p["timeAsSeconds"]?.jsonPrimitive?.intOrNull ?: p.getValue("time").jsonPrimitive.content.split(':').let {
                        it[0].toInt() * 3600 + it[1].toInt() * 60
                    }
                    val rate = p.getValue("value").jsonPrimitive.float
                    require(seconds in 0..86399 && rate.isFinite() && rate >= 0)
                    BasalEntry(seconds, rate)
                }.sortedBy { it.seconds }
                require(entries.isNotEmpty())
                BasalProfile(effective, zone, entries)
            }.getOrNull() }.sortedBy { it.effective }
        }.getOrDefault(emptyList())

        fun read(caches: List<SyncCache>): ForecastTherapy = ForecastTherapy(
            caches.flatMap { cache -> cache.get(SourceSync.THERAPY_COVERAGE).orEmpty().split(';').mapNotNull { row ->
                val p = row.split(','); if (p.size != 2) null else p[0].toLongOrNull()?.let { start ->
                    p[1].toLongOrNull()?.takeIf { it >= start }?.let { TherapyRange(start, it) }
                }
            } }, caches.flatMap { profiles(it.get(SourceSync.BASAL_PROFILES).orEmpty()) }.distinct().sortedBy { it.effective })
    }
}

/** Five-minute raw channels. Basal is U/h; bolus U and carbs g are sums in (t−5m,t]. */
object ForecastFeatures {
    const val STEP = 300_000L
    fun therapy(first: Long, steps: Int, origin: Long, treatments: List<Treatment>, context: ForecastTherapy): List<FloatArray> {
        val basal = FloatArray(steps) { Float.NaN }
        val bolus = FloatArray(steps) { Float.NaN }
        val carbs = FloatArray(steps) { Float.NaN }
        val known = treatments.filter { it.timeMillis <= origin }.sortedBy { it.timeMillis }
        val temps = known.filter { it.isBasal && it.durationMinutes != null }
        val profiles = context.profiles.filter { it.effective <= origin }
        fun rate(at: Long): Float {
            val scheduled = profiles.lastOrNull { it.effective <= at }?.rate(at) ?: Float.NaN
            val temp = temps.lastOrNull { it.timeMillis <= at }?.takeIf { it.tempRunningAt(at) }
            return when {
                temp?.basalRate != null -> temp.basalRate.toFloat()
                temp?.basalPercent != null && scheduled.isFinite() -> scheduled *
                    (if (temp.percentOfProfile) temp.basalPercent / 100 else 1 + temp.basalPercent / 100).toFloat()
                else -> scheduled
            }
        }
        for (i in 0 until steps) {
            val end = first + i * STEP
            val start = end - STEP
            if (context.ranges.any { it.start <= start && it.end >= end } && end <= origin) { bolus[i] = 0f; carbs[i] = 0f }
            if (context.ranges.none { start < it.end && end > it.start }) continue
            val boundaries = (listOf(start, end) + temps.flatMap { listOf(it.timeMillis, it.timeMillis + ((it.durationMinutes ?: 0.0) * 60_000).toLong()) } +
                profiles.flatMap { listOf(it.effective) + it.boundaries(start, end) }).filter { it in start..end }.distinct().sorted()
            var total = 0.0
            for (j in 0 until boundaries.lastIndex) total += rate(boundaries[j]) * (boundaries[j + 1] - boundaries[j]).toDouble() / STEP
            basal[i] = total.toFloat()
        }
        fun add(values: FloatArray, index: Int, amount: Double) {
            if (index in values.indices && amount.isFinite() && amount >= 0) values[index] = (values[index].takeIf(Float::isFinite) ?: 0f) + amount.toFloat()
        }
        val pulseSlots = mutableSetOf<Int>()
        known.forEach { t ->
            val slot = kotlin.math.ceil((t.timeMillis - first).toDouble() / STEP).toInt()
            if (t.isBolus) add(bolus, slot, t.insulin)
            if (t.carbs > 0) add(carbs, slot, t.carbs)
            if (t.isBasal && t.basalRate == null && t.basalPercent == null && t.durationMinutes == null && slot in basal.indices) {
                if (pulseSlots.add(slot)) basal[slot] = 0f
                add(basal, slot, t.insulin * 12)
            }
            if (t.basalRate != null && t.durationMinutes == null && slot in basal.indices && slot !in pulseSlots) basal[slot] = t.basalRate.toFloat()
        }
        return listOf(basal, bolus, carbs)
    }
}
