package io.github.antonkulaga.glucowatch.phone

import glucowatch.core.*
import org.json.JSONArray
import org.json.JSONObject

/** Private versioned derived data; the original recording is retained and verified separately. */
internal object WorkoutJson {
    fun encode(w: Workout) = JSONObject().apply {
        put("version", 1); put("id", w.id); put("name", w.name); put("sport", w.sport)
        put("start", w.startMillis); put("end", w.endMillis)
        put("elapsed", w.elapsedSeconds); put("timer", w.timerSeconds); put("distance", w.distanceM)
        put("ascent", w.ascentM); put("descent", w.descentM); put("pool", w.poolLengthM); put("excluded", w.excludedSampleCount)
        put("heartMean", w.summaryHeartRateMean); put("heartMax", w.summaryHeartRateMax)
        put("samples", JSONArray().apply { w.samples.forEach { s -> put(JSONArray(listOf(s.timeMillis, s.heartRate, s.speedMps, s.elevationM, s.distanceM))) } })
        put("lengths", JSONArray().apply { w.lengths.forEach { s -> put(JSONArray(listOf(s.startMillis, s.endMillis, s.timerSeconds, s.speedMps, s.active))) } })
    }.toString()
    fun decode(text: String): Workout {
        val o = JSONObject(text); require(o.getInt("version") == 1)
        fun n(key: String) = o.optDouble(key).takeIf { it.isFinite() }
        fun JSONArray.n(i: Int) = optDouble(i, Double.NaN).takeIf { it.isFinite() }
        val samples = o.getJSONArray("samples").let { a -> (0 until a.length()).map { i -> a.getJSONArray(i).let { s ->
            WorkoutSample(s.getLong(0), s.n(1)?.toInt(), s.n(2), s.n(3), s.n(4))
        } } }
        val lengths = o.getJSONArray("lengths").let { a -> (0 until a.length()).map { i -> a.getJSONArray(i).let { s ->
            WorkoutLength(s.getLong(0), s.getLong(1), s.n(2), s.n(3), s.getBoolean(4))
        } } }
        return Workout(o.getString("id"), o.getString("name"), o.getString("sport"), o.getLong("start"), o.getLong("end"),
            n("elapsed"), n("timer"), n("distance"), n("ascent"), n("descent"), samples, n("pool"), lengths, o.optInt("excluded"),
            summaryHeartRateMean = n("heartMean"), summaryHeartRateMax = n("heartMax"))
    }
}
