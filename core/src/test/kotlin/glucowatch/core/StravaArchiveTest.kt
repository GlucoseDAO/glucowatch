package glucowatch.core

import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class StravaArchiveTest {
    private val gpx = """<gpx xmlns="http://www.topografix.com/GPX/1/1" xmlns:x="urn:heart"><trk><name>Morning run</name><type>running</type><trkseg>
        <trkpt lat="45" lon="26"><ele>100</ele><time>2026-10-01T10:00:00Z</time><extensions><x:hr>120</x:hr></extensions></trkpt>
        <trkpt lat="45.0001" lon="26"><ele>101</ele><time>2026-10-01T10:00:10Z</time><extensions><x:hr>130</x:hr></extensions></trkpt>
        </trkseg></trk></gpx>""".toByteArray()
    private fun zip(vararg entries: Pair<String, ByteArray>, action: (File) -> Unit) {
        val file = Files.createTempFile("strava-test", ".zip").toFile()
        try { ZipOutputStream(file.outputStream()).use { z -> entries.forEach { (name, data) -> z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() } }; action(file) }
        finally { file.delete() }
    }
    @Test fun gpxRetainsObservationsAndDerivesDistance() {
        val w = WorkoutRecording.read(gpx, "run.gpx")
        assertEquals("Run", w.sport); assertEquals("Morning run", w.name)
        assertEquals(listOf(120, 130), w.samples.map { it.heartRate }); assertEquals(10_000L, w.endMillis - w.startMillis)
        assertTrue(w.distanceM!! in 11.0..11.2)
    }
    @Test fun tcxReadsNamespacedHeartSpeedAndDistance() {
        val xml = """<TrainingCenterDatabase><Activities><Activity Sport="Biking"><Lap><Track>
            <Trackpoint><Time>2026-10-01T10:00:00Z</Time><HeartRateBpm><Value>140</Value></HeartRateBpm><DistanceMeters>0</DistanceMeters><Extensions><Speed>5</Speed></Extensions></Trackpoint>
            <Trackpoint><Time>2026-10-01T10:00:10Z</Time><DistanceMeters>50</DistanceMeters></Trackpoint>
            </Track></Lap></Activity></Activities></TrainingCenterDatabase>"""
        val w = WorkoutRecording.read(xml.toByteArray(), "ride.tcx")
        assertEquals("Ride", w.sport); assertEquals(140, w.samples.first().heartRate)
        assertEquals(5.0, w.samples.first().speedMps); assertEquals(50.0, w.distanceM)
    }
    @Test fun gpxSegmentsAndLongGapsDoNotInventDistance() {
        val xml = gpx.toString(Charsets.UTF_8).replace("10:00:10", "10:10:10")
        assertEquals(0.0, WorkoutRecording.read(xml.toByteArray(), "gap.gpx").distanceM)
    }
    @Test fun gzippedRecordingAndSummaryUseRawMetricColumns() {
        val packed = java.io.ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(gpx) } }.toByteArray()
        val csv = "Activity ID,Activity Date,Activity Name,Activity Type,Elapsed Time,Distance,Filename,Elapsed Time,Distance\n1,\"Oct 1, 2026, 10:00:00 AM\",\"A, named run\",Run,10,0.01,activities/1.gpx.gz,10.0,12.5\n2,\"Oct 1, 2026, 11:00:00 AM\",Manual,Swim,600,0.50,,600.0,500.0\n"
        zip("activities/1.gpx.gz" to packed, "activities.csv" to csv.toByteArray(), "media/ignored.jpg" to byteArrayOf(1)) { file ->
            val records = mutableListOf<StravaArchive.Recording>(); val result = StravaArchive.read(file, records::add)
            assertEquals(2, result.imported); assertEquals(1, result.issues.size)
            assertEquals("A, named run", records[0].workout.name); assertEquals(500.0, records[1].workout.distanceM)
            assertEquals("Swim", records[1].workout.sport); assertEquals(0, records[1].workout.samples.size)
            records.forEach { assertEquals(Workout.digest(it.bytes), it.workout.id) }
        }
    }
    @Test fun unsafeZipPathAndExternalEntitiesAreRejected() {
        zip("../activities/1.gpx" to gpx) { file -> assertFails { StravaArchive.read(file) {} } }
        assertFails { WorkoutRecording.read("<!DOCTYPE gpx [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><gpx>&x;</gpx>".toByteArray(), "bad.gpx") }
        assertFails { StravaArchive.bounded(ByteArray(20).inputStream(), 10) }
    }
    @Test fun invalidRecordingsAreReportedAndStorageErrorsStopImport() {
        zip("activities/good.gpx" to gpx, "activities/bad.fit" to byteArrayOf(1)) { file ->
            val result = StravaArchive.read(file) {}; assertEquals(1, result.imported); assertEquals(1, result.issues.size)
            assertFailsWith<IllegalStateException> { StravaArchive.read(file) { error("Storage full") } }
        }
    }
    @Test fun suppliedArchiveWhenExplicitlyConfigured() {
        val path = System.getenv("STRAVA_SAMPLE_ZIP") ?: return
        var detailed = 0; var summary = 0; var sampleCount = 0L
        val result = StravaArchive.read(File(path)) { r ->
            assertTrue(r.workout.endMillis >= r.workout.startMillis)
            assertEquals(Workout.digest(r.bytes), r.workout.id)
            if (r.workout.samples.isEmpty()) summary++ else detailed++
            sampleCount += r.workout.samples.size
        }
        assertEquals(750, result.imported)
        println("Strava sample: ${result.imported} activities, $detailed detailed, $summary summary-only, $sampleCount observations, ${result.issues.size} notes")
        println("Import note categories: " + result.issues.groupingBy { it.substringAfter(": ").replace(Regex("Recording unavailable \\(.*?\\);"), "Recording unavailable;") }.eachCount())
    }
}
