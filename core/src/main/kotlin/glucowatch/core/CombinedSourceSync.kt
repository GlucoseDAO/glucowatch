package glucowatch.core

/** A source's cache is supplied by the app and must reject writes after its account is changed. */
data class SyncSource(val label: String, val account: SourceAccount, val cache: SyncCache, val problem: String? = null)

data class CombinedData(
    val readings: List<GlucoseReading>,
    val treatments: List<Treatment>,
    val loop: LoopStatus?,
)

/** One CGM trajectory with therapy from each selected pump/loop; failure of one does not stop the others. */
class CombinedSourceSync(
    private val transport: HttpTransport = UrlConnectionTransport(),
    private val retainMs: Long = SourceSync.DAY_MS,
) {
    fun fetch(primary: SyncSource, extras: List<SyncSource>, chartHours: Int, now: Long = System.currentTimeMillis()): List<String> =
        (listOf(primary) + extras).mapIndexedNotNull { index, source ->
            runCatching {
                source.problem?.let { throw IllegalStateException(it) }
                SourceSync(source.cache, transport, retainMs).fetch(source.account, chartHours, now, glucose = index == 0)
            }.exceptionOrNull()?.let { "${source.label}: ${NetworkFailure.describe(it)}" }
        }

    companion object {
        fun read(primary: SyncCache, extras: List<SyncCache>): CombinedData {
            val sources = listOf(primary) + extras
            return CombinedData(
                readings = CacheFormat.decodeReadings(primary.get(SourceSync.READINGS).orEmpty()),
                treatments = mergeTreatments(sources.map { CacheFormat.decodeTreatments(it.get(SourceSync.TREATMENTS).orEmpty()) }),
                loop = sources.mapNotNull { CacheFormat.decodeLoop(it.get(SourceSync.LOOP).orEmpty()) }
                    .sortedBy { it.timeMillis }.fold(null as LoopStatus?) { older, newer -> newer.mergedOnto(older) },
            )
        }
    }
}
