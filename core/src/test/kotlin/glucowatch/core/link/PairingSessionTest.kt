package glucowatch.core.link

import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingSessionTest {
    private var time = 0L

    @Test fun `watch started first waits until phone opens pairing`() {
        val phone = PairingSession<String> { time }
        var attempts = 0
        val search = PairingSearch({ time }) { delay -> time += delay; phone.open() }
        val result = search.await(onRetry = {}) { budget ->
            assertEquals(PairingTiming.ATTEMPT_MS, budget)
            attempts++
            if (!phone.isOpen()) throw IOException("Phone is not ready")
            phone.offer(phone.generation, "same code")
            "same code"
        }
        assertEquals(2, attempts)
        assertEquals(result, phone.pending())
    }

    @Test fun `phone started first waits until watch requests pairing`() {
        val phone = PairingSession<String> { time }
        phone.open()
        time += 60_000
        val result = PairingSearch({ time }) { error("Should connect immediately") }.await(onRetry = { error("Should not retry") }) {
            phone.offer(phone.generation, "same code")
            "same code"
        }
        assertEquals(result, phone.pending())
        assertFalse(phone.isOpen(), "Do not replace a code while the user compares it")
    }

    @Test fun `cancelled and reopened phone rejects old in-flight handshake`() {
        val phone = PairingSession<String> { time }
        phone.open()
        val old = phone.generation
        phone.close()
        assertFailsWith<LinkException> { phone.offer(old, "late") }
        phone.open()
        assertFailsWith<LinkException> { phone.offer(old, "late") }
        assertNull(phone.pending())
        phone.offer(phone.generation, "current")
        assertEquals("current", phone.pending())
    }

    @Test fun `phone expiry rejects handshake and removes stale confirmation`() {
        val phone = PairingSession<String> { time }
        phone.open()
        phone.offer(phone.generation, "code")
        assertFailsWith<LinkException> { phone.offer(phone.generation, "replacement") }
        assertEquals("code", phone.pending())
        time += PairingTiming.WINDOW_MS
        assertTrue(phone.expired())
        assertFalse(phone.isOpen())
        assertNull(phone.pending())
        assertFailsWith<LinkException> { phone.offer(phone.generation, "late") }
    }

    @Test fun `watch stops retrying at deadline and caps final attempt budget`() {
        val budgets = mutableListOf<Long>()
        val search = PairingSearch({ time }) { time += it }
        assertFailsWith<IOException> {
            search.await(onRetry = {}) { budget ->
                budgets += budget
                time += budget - 1 // Variable transport duration, including a final shortened attempt.
                throw IOException("Not ready")
            }
        }
        assertEquals(PairingTiming.WINDOW_MS, time)
        assertTrue(budgets.last() < PairingTiming.ATTEMPT_MS)
        assertTrue(budgets.all { it > 0 && it <= PairingTiming.ATTEMPT_MS })
    }

    @Test fun `cancellation suppresses a successful late response and retries`() {
        val search = PairingSearch({ time }) { error("Must not retry after cancel") }
        assertFailsWith<CancellationException> {
            search.await(onRetry = { error("Must not update UI after cancel") }) {
                search.cancel()
                "late response"
            }
        }
        assertFailsWith<CancellationException> { search.await(onRetry = {}) { error("Cancelled search must not connect") } }
    }
}
