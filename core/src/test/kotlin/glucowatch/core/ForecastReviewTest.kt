package glucowatch.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ForecastReviewTest {
    private val savedAt = 1_000_000_000_000L
    private val step = 5 * 60_000L

    private fun forecast(points: Int) = Prediction(
        "linear",
        (1..points).map { PredictedPoint(savedAt + it * step, 100.0 + it) },
    )

    private fun reading(at: Long, mgdl: Int) = GlucoseReading(at, mgdl, Trend.Flat)

    @Test
    fun `a forecast that meets every point is finished`() {
        val check = PredictionCheck(savedAt, forecast(3))
        val readings = (1..3).map { reading(savedAt + it * step, 90 + it) }
        val review = ForecastReviewer.review(check, readings, now = savedAt + 3 * step)
        assertEquals("Finished", review.status)
        assertEquals("Linear trend", ForecastReviewer.title(review.modelId))
        assertEquals(90 + 2, review.samples[1].actualMgdl)
        assertEquals(102.0, review.samples[1].predictedMgdl)
    }

    @Test
    fun `a hole in the window is an interruption`() {
        val check = PredictionCheck(savedAt, forecast(6))
        val readings = listOf(reading(savedAt + step, 100), reading(savedAt + 6 * step, 110))
        val review = ForecastReviewer.review(check, readings, now = savedAt + 6 * step)
        assertEquals("Interrupted · missing readings", review.status)
        assertNull(review.samples[2].actualMgdl)
    }

    @Test
    fun `a forecast still inside its window stays in progress`() {
        val check = PredictionCheck(savedAt, forecast(6))
        val readings = listOf(reading(savedAt + step, 100), reading(savedAt + 2 * step, 101))
        val review = ForecastReviewer.review(check, readings, now = savedAt + 2 * step + 60_000L)
        assertEquals("In progress", review.status)
    }

    @Test
    fun `no readings yet is waiting until the horizon has passed`() {
        val check = PredictionCheck(savedAt, forecast(3))
        assertEquals("Waiting for readings", ForecastReviewer.review(check, emptyList(), now = savedAt + step).status)
        assertEquals(
            "Interrupted · readings never arrived",
            ForecastReviewer.review(check, emptyList(), now = savedAt + 3 * step).status,
        )
    }
}
