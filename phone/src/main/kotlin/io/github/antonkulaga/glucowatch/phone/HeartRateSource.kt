package io.github.antonkulaga.glucowatch.phone

import android.content.Context
import android.content.pm.PackageManager
import android.health.connect.HealthConnectException
import android.health.connect.HealthConnectManager
import android.health.connect.HealthPermissions
import android.health.connect.ReadRecordsRequestUsingFilters
import android.health.connect.ReadRecordsResponse
import android.health.connect.TimeInstantRangeFilter
import android.health.connect.datatypes.HeartRateRecord
import android.os.Build
import android.os.OutcomeReceiver
import glucowatch.core.HeartSample
import java.time.Instant
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class HeartRateSummary(val bpm: Long, val ageMinutes: Long, val change30Minutes: Long?)

/** Reads optional phone Health Connect data in the foreground; nothing is uploaded or relayed. */
class HeartRateSource(private val context: Context) {
    fun supported() = Build.VERSION.SDK_INT >= 34 && context.getSystemService(HealthConnectManager::class.java) != null

    fun permitted() = Build.VERSION.SDK_INT >= 34 &&
        context.checkSelfPermission(HealthPermissions.READ_HEART_RATE) == PackageManager.PERMISSION_GRANTED

    suspend fun recent(): HeartRateSummary? = summarize(samples(System.currentTimeMillis() - 2 * 3_600_000L))

    /**
     * Heart-rate samples since [fromMillis], averaged into one-minute buckets so a day of a
     * watch's high-rate samples stays small enough to draw. Empty without permission.
     */
    suspend fun samples(fromMillis: Long): List<HeartSample> {
        if (!supported() || !permitted()) return emptyList()
        val manager = context.getSystemService(HealthConnectManager::class.java) ?: return emptyList()
        val now = Instant.now()
        val request = ReadRecordsRequestUsingFilters.Builder(HeartRateRecord::class.java)
            .setTimeRangeFilter(TimeInstantRangeFilter.Builder()
                .setStartTime(Instant.ofEpochMilli(fromMillis)).setEndTime(now).build())
            .setPageSize(5000)
            .setAscending(true)
            .build()
        val response: ReadRecordsResponse<HeartRateRecord> = suspendCancellableCoroutine { continuation ->
            manager.readRecords(request, context.mainExecutor,
                object : OutcomeReceiver<ReadRecordsResponse<HeartRateRecord>, HealthConnectException> {
                    override fun onResult(result: ReadRecordsResponse<HeartRateRecord>) {
                        if (continuation.isActive) continuation.resume(result)
                    }
                    override fun onError(error: HealthConnectException) {
                        if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                    }
                })
        }
        return response.records.flatMap(HeartRateRecord::getSamples)
            .groupBy { it.time.toEpochMilli() / 60_000L }
            .map { (minute, inMinute) -> HeartSample(minute * 60_000L, inMinute.map { it.beatsPerMinute }.average().toInt()) }
            .sortedBy { it.timeMillis }
    }

    companion object {
        /** The latest value, how old it is, and its change over about 30 minutes. */
        fun summarize(samples: List<HeartSample>, now: Long = System.currentTimeMillis()): HeartRateSummary? {
            val latest = samples.lastOrNull() ?: return null
            val earlier = samples.lastOrNull { it.timeMillis <= latest.timeMillis - 25 * 60_000L }
            return HeartRateSummary(latest.bpm.toLong(), (now - latest.timeMillis) / 60_000L,
                earlier?.let { (latest.bpm - it.bpm).toLong() })
        }
    }
}
