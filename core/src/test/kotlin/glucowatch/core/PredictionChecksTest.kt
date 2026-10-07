package glucowatch.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PredictionChecksTest {
    @Test fun `a stored forecast round-trips and the log keeps the newest checks`() {
        val file = File.createTempFile("prediction-checks", ".txt")
        file.deleteOnExit()
        val first = Prediction("linear", listOf(PredictedPoint(1_000L, 110.0, 100.0, 120.0)))
        val stored = PredictionChecks.record(file, first, now = 5_000L)
        assertEquals(5_000L, stored?.savedAt)
        assertEquals(first, stored?.prediction)

        repeat(PredictionChecks.MAX_CHECKS) { index ->
            PredictionChecks.record(
                file,
                Prediction("linear", listOf(PredictedPoint(index.toLong(), 100.0))),
                now = 10_000L + index,
            )
        }
        val loaded = PredictionChecks.load(file)
        assertEquals(PredictionChecks.MAX_CHECKS, loaded.size)
        assertTrue(loaded.none { it.savedAt == 5_000L })
        assertEquals(10_000L + PredictionChecks.MAX_CHECKS - 1, loaded.last().savedAt)
    }

    @Test fun `an empty forecast is not stored and a broken line is skipped`() {
        val file = File.createTempFile("prediction-checks", ".txt")
        file.deleteOnExit()
        assertNull(PredictionChecks.record(file, Prediction("linear", emptyList()), now = 1L))
        file.writeText("not-a-check\n")
        assertEquals(emptyList(), PredictionChecks.load(file))
    }
}
