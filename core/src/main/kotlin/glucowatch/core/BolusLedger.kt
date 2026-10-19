package glucowatch.core

/** Match a native programming record to its delivery by bolus number and component.
 * The public CSV keeps both native rows. This view is for totals, charts and response analysis.
 */
object BolusLedger {
    fun extended(r: ExportRow) = r.programmedExtendedU!=null || r.deliveredExtendedU!=null ||
        r.bolusType?.let { it.contains("square",true)||it.contains("extended",true) }==true
    fun records(rows: List<ExportRow>): List<ExportRow> {
        val native=rows.filter { it.source==ExportRow.SOURCE_CARELINK&&it.kind==ExportRow.KIND_BOLUS }.distinctBy { it.identity() }.sortedBy { it.timeMillis }
        val used=mutableSetOf<ExportRow>()
        val delivered=native.filter { it.insulinU!=null }.map { d->
            val request=if(d.bolusId==null)null else native.lastOrNull { p->p.insulinU==null&&p !in used&&p.bolusId==d.bolusId&&
                extended(p)==extended(d)&&d.timeMillis-p.timeMillis in 0..43_200_000L }
            if(request==null)d else {
                used+=request
                d.copy(timeMillis=request.timeMillis,durationMin=d.durationMin?:request.durationMin,
                    programmedFastU=request.programmedFastU?:d.programmedFastU,
                    programmedExtendedU=request.programmedExtendedU?:d.programmedExtendedU)
            }
        }
        return (delivered+native.filter { it.insulinU==null&&it !in used }).sortedBy { it.timeMillis }
    }
    fun unitsBetween(records: List<ExportRow>,from: Long,until: Long): Double = records.filter { it.insulinU!=null }.sumOf { r->
        val extension=r.deliveredExtendedU?:r.insulinU.takeIf { extended(r) }?:0.0
        val fast=r.deliveredFastU?:(r.insulinU!!-extension).coerceAtLeast(0.0)
        val immediate=if(r.timeMillis in from until until)fast else 0.0
        val duration=r.durationMin?.takeIf {it>0}?.times(60_000)?.toLong()
        immediate+if(extension<=0)0.0 else if(duration==null) {
            if(r.timeMillis in from until until)extension else 0.0
        }else extension*(minOf(until,r.timeMillis+duration)-maxOf(from,r.timeMillis)).coerceAtLeast(0).toDouble()/duration
    }
}
