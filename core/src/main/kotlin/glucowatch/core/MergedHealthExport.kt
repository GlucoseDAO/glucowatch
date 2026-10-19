package glucowatch.core

/** Chronological event union, not a synthetic resampled table. Keep each source and blank field. */
object MergedHealthExport {
    fun rows(native: List<ExportRow>, glucose: List<GlucoseReading>, workouts: List<Workout>,from: Long,until: Long): List<ExportRow> {
        val merged=(native.filter { it.timeMillis in from until until }+workouts.flatMap { it.exportRows(from,until) })
            .distinctBy { it.source+it.identity() }
        // Analyse with all supplied context, then clip output. Filtering context first can
        // invent a clean meal/correction window at the beginning of a CSV sample.
        val estimates=InsulinResponse.analyse(WorkoutAnnotations.add(native,workouts),glucose).exportRows()
            .filter { it.timeMillis in from until until }
        return (merged+estimates).sortedWith(compareBy<ExportRow> {it.timeMillis}.thenBy {it.source}.thenBy {it.kind})
    }
}
