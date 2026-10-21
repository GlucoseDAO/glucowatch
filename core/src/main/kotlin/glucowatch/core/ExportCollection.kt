package glucowatch.core

/** Completed services stay completed across retries; pump glucose never substitutes for Clarity. */
class ExportCollection(content: ExportContent, latest: LatestDevice, window: ExportWindow? = null) {
    // CareLink accepts a month at a time. Use 30 inclusive days, even across calendar months.
    val ranges: Map<ExportPortal, List<ExportDates>> = ExportPortal.entries.associateWith { portal ->
        if (window == null) emptyList() else if (portal == ExportPortal.CLARITY) listOf(ExportDates(window.start, window.end))
        else buildList {
            var end = window.end
            while (end >= window.start) {
                val start = maxOf(window.start, end.minusDays(29))
                add(ExportDates(start, end)); end = start.minusDays(1)
            }
        }
    }
    val portals = if (ranges.getValue(ExportPortal.CARELINK).size > 1 && content.pump)
        listOf(ExportPortal.CARELINK, ExportPortal.CLARITY).filter { it == ExportPortal.CARELINK || content.sensor }
        else ExportPortal.sequence(content, latest)
    private val completed = mutableMapOf<ExportPortal, MutableSet<Int>>()
    fun count(portal: ExportPortal) = maxOf(1, ranges.getValue(portal).size)
    fun collected(portal: ExportPortal) = completed[portal]?.size ?: 0
    fun pending(portal: ExportPortal) = (0 until count(portal)).firstOrNull { it !in completed[portal].orEmpty() }
    fun target(portal: ExportPortal) = pending(portal)?.let { ranges.getValue(portal).getOrNull(it) }
    val next: ExportPortal? get() = portals.firstOrNull { !contains(it) }
    val ready: Boolean get() = portals.all(::contains)
    fun contains(portal: ExportPortal) = collected(portal) == count(portal)
    /** A renamed duplicate cannot advance collection, nor can new events outside the target chunk. */
    fun validate(portal: ExportPortal, rows: List<ExportRow>, previous: List<ExportRow>, zone: java.time.ZoneId): Int {
        require(portal in portals) { "This source is not selected for export." }
        val index = requireNotNull(pending(portal)) { "This source is already complete." }
        val target = target(portal)
        val known = previous.map { it.identity() }.toHashSet()
        val required = if (portal == ExportPortal.CARELINK) ExportContent.PUMP else ExportContent.SENSOR
        val selected = rows.filter { row -> row.source == portal.source && required.includes(row) && (target == null ||
            java.time.Instant.ofEpochMilli(row.timeMillis).atZone(zone).toLocalDate() in target.start..target.end) }
        require(selected.isNotEmpty()) { "Download ${target?.start} to ${target?.end}. This file has no selected events in that interval." }
        require(selected.any { it.identity() !in known }) { "This file is already imported. Download the next displayed interval." }
        return index
    }
    fun accept(portal: ExportPortal, index: Int = pending(portal) ?: 0): Boolean {
        require(portal in portals) { "This source is not selected for export." }
        require(index in 0 until count(portal)) { "Invalid download interval." }
        return completed.getOrPut(portal) { mutableSetOf() }.add(index)
    }
}
