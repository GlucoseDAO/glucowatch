package glucowatch.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.TreeMap

/**
 * Bounded workout-only FIT decoder. Definition/data framing follows the MIT fitdecode reader
 * (Jean-Charles Lefebvre); see licenses/fitdecode.txt. No Garmin SDK or binary dependency.
 * Reads standard record/session/event fields, skips unknown and developer fields by their sizes.
 * Preserve the original file: this is intentionally not a general-purpose FIT re-encoder.
 */
object FitWorkoutReader {
    const val MAX_BYTES = 32 * 1024 * 1024
    private const val MAX_SAMPLES = 300_000
    private val epoch = Instant.parse("1989-12-31T00:00:00Z").toEpochMilli()
    private data class Field(val number: Int, val size: Int, val type: Int)
    private data class Definition(val global: Int, val big: Boolean, val fields: List<Field>, val developerBytes: Int)

    fun read(bytes: ByteArray, name: String = "Workout"): Workout {
        require(bytes.size in 14..MAX_BYTES) { "Choose a FIT workout smaller than 32 MB." }
        val header = bytes[0].toInt() and 255
        require(header in listOf(12, 14) && bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == ".FIT") { "This is not a FIT file." }
        val size = ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        require(size > 0 && header + size + 2 == bytes.size.toLong()) { "FIT download is incomplete or has unsupported trailing data." }
        require(crc(bytes, bytes.size) == 0) { "FIT checksum failed. Download the original file again." }
        if (header == 14) require(crc(bytes, 14) == 0) { "FIT header checksum failed." }
        val end = header + size.toInt()
        var at = header
        fun byte(): Int { require(at < end) { "Truncated FIT message." }; return bytes[at++].toInt() and 255 }
        fun unsigned(count: Int, big: Boolean): Long {
            var n = 0L
            repeat(count) { i -> n = n or (byte().toLong() shl (8 * if (big) count - 1 - i else i)) }
            return n
        }
        val definitions = arrayOfNulls<Definition>(16)
        val samples = TreeMap<Long, WorkoutSample>()
        var timestamp: Long? = null
        var start: Long? = null
        var stop: Long? = null
        var sessionEnd: Long? = null
        var elapsed: Double? = null
        var timer: Double? = null
        var distance: Double? = null
        var ascent: Double? = null
        var descent: Double? = null
        var sport = "Exercise"
        var sessions = 0
        fun time(value: Double?) = value?.toLong()?.takeIf { it >= 0 }?.let { epoch + it * 1000 }
        while (at < end) {
            val recordHeader = byte()
            val compressed = recordHeader and 128 != 0
            val local = if (compressed) (recordHeader shr 5) and 3 else recordHeader and 15
            if (!compressed && recordHeader and 64 != 0) {
                byte() // reserved
                val architecture = byte()
                require(architecture in 0..1) { "Unsupported FIT byte order." }
                val global = unsigned(2, architecture == 1).toInt()
                val fields = List(byte()) { Field(byte(), byte(), byte() and 31) }
                require(fields.all { it.size > 0 }) { "Invalid FIT field size." }
                var extra = 0
                if (recordHeader and 32 != 0) repeat(byte()) { byte(); extra += byte(); byte() }
                definitions[local] = Definition(global, architecture == 1, fields, extra)
                continue
            }
            val definition = definitions[local] ?: error("Undefined FIT message.")
            val values = mutableMapOf<Int, Double>()
            for (field in definition.fields) {
                require(at + field.size <= end) { "Truncated FIT field." }
                val width = when (field.type) { 0, 1, 2, 10, 13 -> 1; 3, 4, 11 -> 2; 5, 6, 8, 12 -> 4; 9, 14, 15, 16 -> 8; else -> 0 }
                if (width == 0 || field.size != width) { at += field.size; continue }
                val raw = unsigned(width, definition.big)
                val zeroInvalid = field.type in listOf(10, 11, 12, 16)
                val signed = field.type in listOf(1, 3, 5, 14)
                val invalid = if (zeroInvalid) 0L else if (signed) (1L shl (width * 8 - 1)) - 1 else if (width == 8) -1L else (1L shl (width * 8)) - 1
                if (raw == invalid) continue
                val value = when (field.type) {
                    8 -> Float.fromBits(raw.toInt()).toDouble()
                    9 -> Double.fromBits(raw)
                    else -> (if (signed && width < 8) (raw shl (64 - width * 8)) shr (64 - width * 8) else raw).toDouble()
                }
                if (value.isFinite()) values[field.number] = value
            }
            require(at + definition.developerBytes <= end) { "Truncated FIT developer fields." }
            at += definition.developerBytes
            values[253]?.toLong()?.let { timestamp = it }
            if (compressed) {
                val previous = timestamp ?: error("FIT compressed timestamp has no baseline.")
                val offset = (recordHeader and 31).toLong()
                timestamp = (previous and 31L.inv()) + offset + if (offset < (previous and 31)) 32 else 0
            }
            val t = timestamp?.let { epoch + it * 1000 }
            when (definition.global) {
                18 -> {
                    require(++sessions == 1) { "Import one workout per FIT file; multisport sessions are not yet supported." }
                    start = time(values[2]) ?: start
                    sessionEnd = time(values[253])
                    elapsed = values[7]?.div(1000)
                    timer = values[8]?.div(1000)
                    distance = values[9]?.div(100)
                    ascent = values[22]; descent = values[23]
                    sport = when (values[5]?.toInt()) { 1 -> "Run"; 2 -> "Ride"; 5 -> "Swim"; 11 -> "Walk"; 17 -> "Hike"; else -> "Exercise" }
                }
                21 -> if (values[0]?.toInt() == 0 && values[1]?.toInt() in listOf(1, 4, 8, 9)) stop = t
                20 -> {
                    require(t != null) { "FIT observation has no timestamp." }
                    val previous = samples[t] ?: WorkoutSample(t)
                    samples[t] = previous.copy(
                        heartRate = values[3]?.toInt()?.takeIf { it in 1..250 } ?: previous.heartRate,
                        speedMps = (values[73] ?: values[6])?.div(1000)?.takeIf { it >= 0 } ?: previous.speedMps,
                        elevationM = (values[78] ?: values[2])?.let { it / 5 - 500 } ?: previous.elevationM,
                        distanceM = values[5]?.div(100)?.takeIf { it >= 0 } ?: previous.distanceM,
                    )
                    require(samples.size <= MAX_SAMPLES) { "FIT workout has too many observations." }
                }
            }
        }
        require(sessions == 1 && samples.isNotEmpty()) { "No workout session and timestamped observations found in this FIT." }
        val first = start ?: samples.firstKey()
        val last = maxOf(stop ?: sessionEnd ?: (first + ((elapsed ?: 0.0) * 1000).toLong()), samples.lastKey())
        require(last > first && last - first <= 7 * 86_400_000L) { "Invalid workout interval." }
        require(samples.keys.all { it in first..last }) { "FIT observations fall outside the workout." }
        return Workout(Workout.digest(bytes), name.substringBeforeLast('.').take(120).ifBlank { sport }, sport,
            first, last, elapsed, timer, distance, ascent, descent, samples.values.toList())
    }

    /** FIT CRC-16, including the stored checksum. A complete valid file yields zero. */
    internal fun crc(bytes: ByteArray, length: Int): Int {
        var crc = 0
        for (i in 0 until length) {
            crc = crc xor (bytes[i].toInt() and 255)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc
    }
}
