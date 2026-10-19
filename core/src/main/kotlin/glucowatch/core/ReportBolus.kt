package glucowatch.core

/** Graph components preserve the distinction between delivered units and scheduled extensions. */
data class ReportBolus(val start: Long, val immediate: Double?, val extended: Double?, val end: Long?,
                       val extensionDelivered: Boolean, val bolusId: String? = null) {
    val total get() = (immediate?:0.0)+(extended?:0.0)
    fun cumulative(time: Long): Double = (immediate?:0.0)+(extended?:0.0)*
        (end?.takeIf { it>start }?.let { ((time-start).toDouble()/(it-start)).coerceIn(0.0,1.0) }?:0.0)
    companion object {
        fun read(rows: List<ExportRow>): List<ReportBolus> {
            val parts=BolusLedger.records(rows).map { r ->
            val square = r.bolusType?.let { it.contains("square", true) || it.contains("extended", true) } == true
            val extended = r.deliveredExtendedU ?: r.programmedExtendedU ?: r.insulinU.takeIf { square }
            val fast = r.deliveredFastU ?: r.insulinU.takeIf { !square && r.deliveredExtendedU == null && r.programmedExtendedU == null }
            ReportBolus(r.timeMillis, fast, extended,
                r.durationMin?.takeIf { it.isFinite() && it > 0 && it <= 1440 }?.let { r.timeMillis + (it * 60_000).toLong() },
                r.deliveredExtendedU != null || (square && r.insulinU != null),r.bolusId)
            }
            val used=mutableSetOf<ReportBolus>()
            return parts.filter { it.extended!=null }.map { e->
                val fast=parts.filter { it.extended==null&&it.immediate!=null&&it !in used&&
                    ((e.bolusId!=null&&it.bolusId==e.bolusId) || (e.bolusId==null&&it.bolusId==null&&it.start==e.start))&&
                    kotlin.math.abs(it.start-e.start)<=600_000 }.minByOrNull { kotlin.math.abs(it.start-e.start) }
                if(fast==null)e else {used+=fast;e.copy(start=minOf(e.start,fast.start),immediate=fast.immediate)}
            }+parts.filter { it.extended==null&&it !in used }
        }
    }
}
