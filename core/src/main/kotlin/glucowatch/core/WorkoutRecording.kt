package glucowatch.core

import org.xml.sax.Attributes
import org.xml.sax.ext.DefaultHandler2
import org.xml.sax.SAXException
import java.time.Instant
import java.util.zip.GZIPInputStream
import javax.xml.parsers.SAXParserFactory
import kotlin.math.*

/** Sparse native GPX/TCX observations, with bounded XML and external entities disabled. */
object WorkoutRecording {
    fun read(bytes: ByteArray, filename: String): Workout {
        require(bytes.size <= FitWorkoutReader.MAX_BYTES) { "Activity exceeds 32 MB." }
        if (filename.endsWith(".gz", true)) return read(StravaArchive.bounded(GZIPInputStream(bytes.inputStream()), FitWorkoutReader.MAX_BYTES), filename.dropLast(3))
        if (filename.endsWith(".fit", true)) return FitWorkoutReader.read(bytes, filename)
        require(filename.endsWith(".gpx", true) || filename.endsWith(".tcx", true)) { "Choose FIT, GPX or TCX." }
        val handler = Handler()
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            // Android's Expat does not implement Xerces' disallow-doctype feature. The
            // mandatory lexical handler below rejects DTDs on both platforms.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        factory.newSAXParser().xmlReader.apply {
            contentHandler = handler; errorHandler = handler; entityResolver = handler
            setProperty("http://xml.org/sax/properties/lexical-handler", handler)
        }.parse(org.xml.sax.InputSource(bytes.inputStream()))
        val samples = handler.samples.distinctBy { it.timeMillis }.sortedBy { it.timeMillis }
        val start = samples.firstOrNull()?.timeMillis ?: error("No timestamped activity samples.")
        val end = samples.last().timeMillis
        require(end >= start && end - start <= 7 * 86400_000L) { "Invalid activity interval." }
        return Workout(Workout.digest(bytes), handler.name.ifBlank { filename.substringAfterLast('/').substringBeforeLast('.') },
            StravaArchive.sport(handler.sport), start, end, (end - start) / 1000.0, null,
            samples.lastOrNull()?.distanceM, null, null, samples)
    }
    private class Handler : DefaultHandler2() {
        override fun startDTD(name: String?, publicId: String?, systemId: String?) { throw SAXException("DTDs are not permitted in activity files.") }
        override fun resolveEntity(publicId: String?, systemId: String?): org.xml.sax.InputSource = throw SAXException("External entities are not permitted.")
        val samples = mutableListOf<WorkoutSample>(); var name = ""; var sport = ""
        private val path = mutableListOf<String>(); private var text = StringBuilder()
        private var point = false; private var time: Long? = null; private var hr: Int? = null
        private var altitude: Double? = null; private var distance: Double? = null; private var speed: Double? = null
        private var lat: Double? = null; private var lon: Double? = null
        private var previous: Triple<Long, Double, Double>? = null; private var travelled = 0.0
        override fun startElement(uri: String?, localName: String, qName: String?, attributes: Attributes) {
            path += localName; text = StringBuilder()
            if (localName == "trkseg") previous = null
            if (localName == "Activity") sport = attributes.getValue("Sport").orEmpty()
            if (localName in setOf("trkpt", "Trackpoint")) {
                require(samples.size < 300_000) { "Activity exceeds 300,000 samples." }
                point = true; time = null; hr = null; altitude = null; distance = null; speed = null
                lat = attributes.getValue("lat")?.toDoubleOrNull(); lon = attributes.getValue("lon")?.toDoubleOrNull()
            }
            require(path.size <= 64) { "XML is too deeply nested." }
        }
        override fun characters(ch: CharArray, start: Int, length: Int) {
            require(text.length + length <= 64_000) { "XML text is too long." }; text.append(ch, start, length)
        }
        override fun endElement(uri: String?, localName: String, qName: String?) {
            val value = text.toString().trim()
            fun number() = value.toDoubleOrNull()?.takeIf { it.isFinite() }
            if (point) when (localName) {
                "time", "Time" -> time = Instant.parse(value).toEpochMilli()
                "hr" -> hr = value.toIntOrNull()?.takeIf { it in 1..250 }
                "Value" -> if ("HeartRateBpm" in path) hr = value.toIntOrNull()?.takeIf { it in 1..250 }
                "ele", "AltitudeMeters" -> altitude = number()
                "DistanceMeters" -> distance = number()?.takeIf { it >= 0 }
                "Speed", "speed" -> speed = number()?.takeIf { it >= 0 }
                "LatitudeDegrees" -> lat = number()
                "LongitudeDegrees" -> lon = number()
            }
            if (!point && localName == "name" && "trk" in path && name.isBlank()) name = value
            if (!point && localName == "type" && "trk" in path) sport = value
            if (localName in setOf("trkpt", "Trackpoint")) {
                time?.let { t ->
                    val latitude = lat?.takeIf { it in -90.0..90.0 }; val longitude = lon?.takeIf { it in -180.0..180.0 }
                    if (latitude != null && longitude != null) {
                        previous?.let { (before, a, b) ->
                            if (t - before in 1..WorkoutAnalysis.MAX_GAP) {
                                val dLat = Math.toRadians(latitude - a); val dLon = Math.toRadians(longitude - b)
                                val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a)) * cos(Math.toRadians(latitude)) * sin(dLon / 2).pow(2)
                                travelled += 6371000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
                            }
                        }
                        previous = Triple(t, latitude, longitude)
                        if (distance == null) distance = travelled
                    } else previous = null
                    samples += WorkoutSample(t, hr, speed, altitude, distance)
                }
                point = false
            }
            path.removeAt(path.lastIndex); text = StringBuilder()
        }
    }
}
