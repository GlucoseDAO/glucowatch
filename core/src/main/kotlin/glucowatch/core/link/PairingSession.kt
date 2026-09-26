package glucowatch.core.link

import java.io.IOException
import java.util.concurrent.CancellationException

/** Shared setup timing; this does not change the Bluetooth protocol or existing pairings. */
object PairingTiming {
    const val WINDOW_MS = 3 * 60_000L
    const val ATTEMPT_MS = 8_000L
    const val RETRY_MS = 2_000L
    fun monotonicMillis() = System.nanoTime() / 1_000_000L
}

/** A cancelled/expired session cannot accept a late handshake, or replace an already displayed code. */
class PairingSession<T>(private val clock: () -> Long = PairingTiming::monotonicMillis) {
    private var deadline = 0L
    private var active = false
    private var offered: T? = null
    @Volatile var generation = 0L
        private set

    @Synchronized fun open() {
        generation++
        active = true
        offered = null
        deadline = clock() + PairingTiming.WINDOW_MS
    }

    @Synchronized fun close() {
        generation++
        active = false
        offered = null
    }

    @Synchronized fun isOpen(generation: Long = this.generation) =
        active && generation == this.generation && clock() < deadline && offered == null

    @Synchronized fun pending(): T? = offered.takeIf { active && clock() < deadline }

    @Synchronized fun expired() = active && clock() >= deadline

    @Synchronized fun offer(generation: Long, value: T) {
        if (!isOpen(generation)) throw LinkException("Pairing ended or a code is already waiting. On the phone, cancel or tap Pair a watch again.")
        offered = value
    }
}

/** Runs on the watch's IO thread. Each attempt receives a budget for the whole bonded-device search. */
class PairingSearch(
    private val clock: () -> Long = PairingTiming::monotonicMillis,
    private val pause: (Long) -> Unit = Thread::sleep,
) {
    @Volatile private var cancelled = false
    fun cancel() { cancelled = true }

    fun <T> await(onRetry: (IOException) -> Unit, attempt: (Long) -> T): T {
        val deadline = clock() + PairingTiming.WINDOW_MS
        while (true) {
            checkCancelled()
            val remaining = deadline - clock()
            if (remaining <= 0) throw IOException("Pairing timed out. Start pairing again on either device.")
            try {
                val result = attempt(minOf(PairingTiming.ATTEMPT_MS, remaining))
                checkCancelled()
                return result
            } catch (e: IOException) {
                checkCancelled()
                onRetry(e)
            }
            pause(minOf(PairingTiming.RETRY_MS, (deadline - clock()).coerceAtLeast(0)))
        }
    }

    private fun checkCancelled() {
        if (cancelled) throw CancellationException("Pairing cancelled")
    }
}
