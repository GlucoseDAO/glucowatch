package glucowatch.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.time.ZoneId
import kotlin.test.*

class WorkoutTest {
    private fun fixture(big: Boolean = false, compressed: Boolean = false, developer: Boolean = false, sport: Int = 1, outside: Boolean = false, records: Boolean = true, pool: Boolean = false, offset: Long = 0): ByteArray {
        val out = ByteArrayOutputStream()
        fun b(vararg values: Int) = values.forEach { out.write(it) }
        fun u(value: Long, bytes: Int) { val v=if(bytes==4&&value in 999_999_000L..1_000_001_000L)value+offset else value
            repeat(bytes) { i -> out.write((v shr (8 * if (big) bytes - i - 1 else i)).toInt()) } }
        b(0x40, 0, if (big) 1 else 0); u(18, 2); b(if(pool)5 else 4, 2, 4, 0x86, 5, 1, 0, 7, 4, 0x86, 9, 4, 0x86)
        if(pool)b(44,2,0x84)
        b(0); u(1_000_000_000, 4); b(sport); u(10_000, 4); u(1_000, 4)
        if(pool)u(2500,2)
        if(records){
        b(0x41, 0, if (big) 1 else 0); u(20, 2); b(2, 253, 4, 0x86, 3, 1, 2)
        b(1); u(if(outside)999_999_998 else 1_000_000_000, 4); b(120)
        // Distance arrives in a different local message at the same timestamp.
        b(if (developer) 0x62 else 0x42, 0, if (big) 1 else 0); u(20, 2); b(2, 253, 4, 0x86, 5, 4, 0x86)
        if (developer) b(1, 0, 3, 0) // one unknown developer field, three bytes
        b(2); u(1_000_000_000, 4); u(500, 4)
        if (developer) b(123, 45, 67)
        b(1); u(1_000_000_001, 4); b(255) // invalid sentinel stays missing
        b(1); u(1_000_000_002, 4); b(140)
        if (compressed) {
            b(0x43, 0, if (big) 1 else 0); u(20, 2); b(1, 3, 1, 2)
            b(0xE3, 150) // local 3; compressed five-bit timestamp = 3
        }
        if(outside){b(1);u(1_000_000_012,4);b(130)}
        }
        if(pool){
            b(0x44,0,if(big)1 else 0);u(101,2);b(5,2,4,0x86,253,4,0x86,4,4,0x86,12,1,0,6,2,0x84)
            b(4);u(1_000_000_000,4);u(1_000_000_010,4);u(10_000,4);b(1);u(0xffff,2)
        }
        val data = out.toByteArray()
        val header = byteArrayOf(12, 0x20, 0, 0) + ByteArray(4) { (data.size shr (8 * it)).toByte() } + ".FIT".toByteArray()
        val file = header + data
        val crc = FitWorkoutReader.crc(file, file.size)
        return file + byteArrayOf(crc.toByte(), (crc shr 8).toByte())
    }

    @Test fun decodesBothByteOrdersAndMissingValues() {
        listOf(false, true).forEach { big ->
            val workout = FitWorkoutReader.read(fixture(big), "Example.fit")
            assertEquals("Example", workout.name)
            assertEquals("Run", workout.sport)
            assertEquals(10.0, workout.distanceM)
            assertEquals(listOf(120, null, 140), workout.samples.map { it.heartRate })
            assertEquals(5.0, workout.samples.first().distanceM)
            assertEquals(10_000, workout.endMillis - workout.startMillis)
        }
    }
    @Test fun browserSuffixesDoNotIdentifyDuplicates() {
        val a=FitWorkoutReader.read(fixture(),"Swim.fit")
        val alias=FitWorkoutReader.read(fixture(),"Swim (1).fit")
        val other=FitWorkoutReader.read(fixture(offset=86400),"Swim (2).fit")
        assertEquals(a.id,alias.id)
        assertNotEquals(a.id,other.id)
        assertEquals(86_400_000,other.startMillis-a.startMillis)
        assertNotEquals(a.fileName(ZoneId.of("UTC")),other.fileName(ZoneId.of("UTC")))
        assertTrue(a.fileName(ZoneId.of("UTC")).contains("_to_"))
        assertTrue(a.fileName(ZoneId.of("UTC")).contains("workout-run"))
    }
    @Test fun compressedTimestampsAndUnknownDeveloperFields() {
        val workout = FitWorkoutReader.read(fixture(big = true, compressed = true, developer = true))
        assertEquals(listOf(120, null, 140, 150), workout.samples.map { it.heartRate })
        assertEquals(3000, workout.samples.last().timeMillis - workout.startMillis)
        assertEquals(5.0, workout.samples.first().distanceM)
    }
    @Test fun rejectsCorruptAndTruncatedFiles() {
        assertFailsWith<IllegalArgumentException> { FitWorkoutReader.read(fixture().dropLast(2).toByteArray()) }
        assertFailsWith<IllegalArgumentException> { FitWorkoutReader.read(fixture().also { it[23] = 12 }) }
    }
    @Test fun boundariesAndZoneGaps() {
        val w = FitWorkoutReader.read(fixture())
        assertTrue(w.overlaps(w.startMillis + 1, w.endMillis + 1))
        assertFalse(w.overlaps(w.endMillis + 1, w.endMillis + 2))
        assertFalse(w.overlaps(w.endMillis, w.endMillis + 2))
        assertFalse(w.overlaps(w.startMillis - 10, w.startMillis))
        assertEquals(listOf(0.0, 2.0, 0.0), w.zoneSeconds(listOf(100, 130)))
        val gap = w.copy(samples = listOf(WorkoutSample(w.startMillis, 120), WorkoutSample(w.startMillis + 20_000, 140)))
        assertEquals(0.0, gap.zoneSeconds(listOf(100, 130)).sum())
        assertTrue(w.fileName(ZoneId.of("Europe/Bucharest")).endsWith("_${w.id.take(12)}.fit"))
    }
    @Test fun sessionBoundsClipWarmupAndRecoveryHeartRate() {
        val workout = FitWorkoutReader.read(fixture(sport = 5, outside = true))
        assertEquals("Swim", workout.sport)
        assertEquals(2, workout.excludedSampleCount)
        assertEquals(10_000, workout.endMillis - workout.startMillis)
        assertTrue(workout.samples.all { it.timeMillis in workout.startMillis..workout.endMillis })
    }
    @Test fun poolLengthsAndSummaryOnlyActivitiesDoNotRequireGpsRecords() {
        listOf(false, true).forEach { big ->
            val swim = FitWorkoutReader.read(fixture(big = big, sport = 5, records = false, pool = true))
            assertTrue(swim.samples.isEmpty())
            assertEquals(25.0, swim.poolLengthM)
            assertEquals(1, swim.lengths.count { it.active })
            val analysis = WorkoutAnalysis.analyse(swim)
            assertTrue(analysis.gap.isEmpty())
            assertEquals("min/100m", analysis.motionUnit)
            assertEquals(40.0 / 60, analysis.motion.single().first().value, 1e-9)
        }
        listOf(1, 2, 5, 10, 11, 17, 254).forEach { sport ->
            val workout = FitWorkoutReader.read(fixture(sport = sport, records = false))
            assertTrue(workout.samples.isEmpty())
            assertTrue(WorkoutAnalysis.analyse(workout).heart.isEmpty())
        }
    }
    @Test fun compressedHeaderReplacesTimestampDefinedInRecord() {
        val bytes = fixture()
        val data = bytes.copyOfRange(12, bytes.size - 2) + byteArrayOf(0xA3.toByte(), 155.toByte())
        val header = byteArrayOf(12, 0x20, 0, 0) + ByteArray(4) { (data.size shr (8 * it)).toByte() } + ".FIT".toByteArray()
        val raw = header + data
        val crc = FitWorkoutReader.crc(raw, raw.size)
        val workout = FitWorkoutReader.read(raw + byteArrayOf(crc.toByte(), (crc shr 8).toByte()))
        assertEquals(155, workout.samples.last().heartRate)
        assertEquals(3000, workout.samples.last().timeMillis - workout.startMillis)
    }
    @Test fun userSuppliedFileWhenExplicitlySelected() {
        val path = System.getenv("GLUCOWATCH_WORKOUT_FIXTURE") ?: return
        val file = File(path)
        val w = FitWorkoutReader.read(file.readBytes(), file.name)
        assertTrue(w.samples.size > 1)
        assertTrue(w.samples.zipWithNext().all { (a,b) -> a.timeMillis < b.timeMillis })
        assertTrue(w.startMillis <= w.samples.first().timeMillis && w.endMillis >= w.samples.last().timeMillis)
        assertEquals(Workout.digest(file.readBytes()), w.id)
    }

    @Test fun privateActivityFixturesWhenExplicitlySelected() {
        val folder = System.getenv("GLUCOWATCH_WORKOUT_FIXTURES")?.let(::File) ?: return
        val files = folder.listFiles().orEmpty().filter { it.extension.equals("fit", true) }
        assertTrue(files.isNotEmpty())
        files.forEach { file ->
            val workout = FitWorkoutReader.read(file.readBytes(), file.name)
            assertTrue(workout.endMillis > workout.startMillis, file.name)
            assertTrue(workout.samples.zipWithNext().all { (a, b) -> a.timeMillis < b.timeMillis }, file.name)
            assertTrue(workout.samples.all { it.timeMillis in workout.startMillis..workout.endMillis }, file.name)
            val result = WorkoutAnalysis.analyse(workout)
            assertTrue(result.heart.flatten().all { it.value.isFinite() }, file.name)
        }
    }
}
