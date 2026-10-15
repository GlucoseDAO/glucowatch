package glucowatch.core

/** Completed services stay completed across retries; pump glucose never substitutes for Clarity. */
class ExportCollection(content: ExportContent, latest: LatestDevice) {
    val portals = ExportPortal.sequence(content, latest)
    private val completed = mutableSetOf<ExportPortal>()
    val next: ExportPortal? get() = portals.firstOrNull { it !in completed }
    val ready: Boolean get() = portals.all { it in completed }
    fun contains(portal: ExportPortal) = portal in completed
    fun accept(portal: ExportPortal): Boolean {
        require(portal in portals) { "This source is not selected for export." }
        return completed.add(portal)
    }
}
