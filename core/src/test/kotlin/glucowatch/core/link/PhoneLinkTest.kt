package glucowatch.core.link

import glucowatch.core.CacheFormat
import glucowatch.core.CombinedSourceSync
import glucowatch.core.DemoData
import glucowatch.core.GlucoseReading
import glucowatch.core.InsulinKind
import glucowatch.core.LoopStatus
import glucowatch.core.SourceSync
import glucowatch.core.SyncCache
import glucowatch.core.Treatment
import glucowatch.core.Trend
import glucowatch.core.LinearTrendPredictor
import glucowatch.core.NightscoutApi
import glucowatch.core.Region
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhoneLinkTest {
    private val watchId = LinkCrypto.randomBytes(PhoneLink.ID_BYTES)
    private val now = 1_745_000_000_000L

    private class FakePhone(var open: Boolean = true) : PhoneLinkHandler {
        override val phoneId = LinkCrypto.randomBytes(PhoneLink.ID_BYTES)
        override val phoneName = "Test phone"
        val keys = mutableMapOf<String, ByteArray>()
        var offered: PairingKeys? = null
        var horizon = -1
        var relay: LinkSnapshot? = null

        override fun pairingOpen() = open

        override fun offerPairing(watchId: ByteArray, keys: PairingKeys) {
            offered = keys
            this.keys[watchId.contentToString()] = keys.key
        }

        override fun keyFor(watchId: ByteArray) = keys[watchId.contentToString()]

        override fun snapshot(horizonMinutes: Int): LinkSnapshot {
            horizon = horizonMinutes
            relay?.let { return it }
            val readings = DemoData.readings(1_745_000_000_000L)
            return LinkSnapshot(
                upstream = "demo",
                sourceLabel = "Demo data",
                readings = readings,
                treatments = DemoData.treatments(1_745_000_000_000L),
                loop = DemoData.loopStatus(1_745_000_000_000L),
                forecast = LinearTrendPredictor().predict(readings, horizonMinutes),
            )
        }

        override fun account() = LinkAccount(LinkSource.NIGHTSCOUT, nightscoutUrl = "https://ns.example", nightscoutToken = "tok-ен", nightscoutApi = NightscoutApi.V3)
    }

    /** Runs [watch] against [PhoneLinkServer] over a pair of in-memory pipes, like one RFCOMM connection. */
    private fun <T> connect(phone: PhoneLinkHandler, watch: (InputStream, OutputStream) -> T): T {
        val toPhone = PipedOutputStream()
        val phoneIn = PipedInputStream(toPhone, 1 shl 16)
        val toWatch = PipedOutputStream()
        val watchIn = PipedInputStream(toWatch, 1 shl 16)
        val server = thread { PhoneLinkServer.serve(phoneIn, toWatch, phone); toWatch.close() }
        try {
            return watch(watchIn, toPhone)
        } finally {
            toPhone.close()
            server.join(5_000)
        }
    }

    private fun paired(phone: FakePhone): ByteArray {
        val offer = connect(phone) { i, o -> WatchLinkClient(watchId).pair(i, o) }
        return offer.keys.key
    }

    @Test
    fun `pairing gives both sides the same key and code`() {
        val phone = FakePhone()
        val offer = connect(phone) { i, o -> WatchLinkClient(watchId).pair(i, o) }
        val phoneSide = assertNotNull(phone.offered)
        assertContentEquals(phoneSide.key, offer.keys.key)
        assertEquals(phoneSide.code, offer.keys.code)
        assertTrue(offer.keys.code.matches(Regex("\\d{6}")))
        assertEquals(7, offer.keys.displayCode.length)
        assertContentEquals(phone.phoneId, offer.phoneId)
        assertEquals("Test phone", offer.phoneName)
    }

    @Test
    fun `a phone that is not pairing turns the watch away`() {
        val phone = FakePhone(open = false)
        val e = assertFailsWith<LinkException> { connect(phone) { i, o -> WatchLinkClient(watchId).pair(i, o) } }
        assertTrue("Pair a watch" in e.message!!)
        assertNull(phone.offered)
    }

    @Test
    fun `sync relays the phone's day and forecast`() {
        val phone = FakePhone()
        val key = paired(phone)
        val snapshot = connect(phone) { i, o -> WatchLinkClient(watchId).sync(i, o, key, horizonMinutes = 30) }
        assertEquals(30, phone.horizon)
        assertEquals(DemoData.readings(now), snapshot.readings)
        assertEquals(DemoData.treatments(now), snapshot.treatments)
        assertEquals(DemoData.loopStatus(now), snapshot.loop)
        assertEquals(LinearTrendPredictor.ID, snapshot.forecast?.modelId)
        assertEquals(6, snapshot.forecast?.points?.size)
        assertEquals("Demo data", snapshot.sourceLabel)
        assertNull(snapshot.error)
    }

    private class MemoryCache : SyncCache {
        val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key]
        override fun put(values: Map<String, String?>) {
            values.forEach { (k, v) -> if (v == null) this.values.remove(k) else this.values[k] = v }
        }
    }

    @Test
    fun `sync relays glucose from the primary source and pump therapy from the secondary`() {
        val dexcom = MemoryCache()
        val carelink = MemoryCache()
        val readings = listOf(GlucoseReading(now - 300_000L, 118, Trend.Flat), GlucoseReading(now, 121, Trend.FortyFiveUp))
        val bolus = Treatment(now - 3_600_000L, insulin = 3.5, carbs = 40.0)
        val auto = Treatment(now - 1_800_000L, insulin = 0.2, automatic = true)
        val basal = Treatment(now - 900_000L, insulinKind = InsulinKind.BASAL, basalRate = 0.65, durationMinutes = 30.0)
        dexcom.put(mapOf(SourceSync.READINGS to CacheFormat.encodeReadings(readings)))
        // The pump's own sensor values never reach the chart; its insulin and IOB do.
        carelink.put(mapOf(
            SourceSync.READINGS to CacheFormat.encodeReadings(listOf(GlucoseReading(now, 240, Trend.Flat))),
            SourceSync.TREATMENTS to CacheFormat.encodeTreatments(listOf(bolus, auto, basal)),
            SourceSync.LOOP to CacheFormat.encodeLoop(LoopStatus(now - 60_000L, iob = 2.4)),
        ))
        val combined = CombinedSourceSync.read(dexcom, listOf(carelink))
        val phone = FakePhone()
        phone.relay = LinkSnapshot("dexcom+carelink", "Dexcom Share + CareLink (MiniMed) insulin",
            combined.readings, combined.treatments, combined.loop)
        val key = paired(phone)

        val snapshot = connect(phone) { i, o -> WatchLinkClient(watchId).sync(i, o, key, horizonMinutes = 0) }
        assertEquals(readings, snapshot.readings)
        assertEquals(listOf(bolus, auto, basal), snapshot.treatments)
        assertEquals(0.65, snapshot.treatments.single { it.isBasal }.basalRate)
        assertEquals(2.4, snapshot.loop?.iob)
        assertEquals("Dexcom Share + CareLink (MiniMed) insulin", snapshot.sourceLabel)
    }

    @Test
    fun `account copies the phone's login`() {
        val phone = FakePhone()
        val key = paired(phone)
        val account = connect(phone) { i, o -> WatchLinkClient(watchId).account(i, o, key) }
        assertEquals(phone.account(), account)
        assertEquals(Region.OUS, account.region)
    }

    @Test
    fun `an unknown watch or a wrong key gets nothing`() {
        val phone = FakePhone()
        val stranger = assertFailsWith<LinkException> {
            connect(phone) { i, o -> WatchLinkClient(watchId).sync(i, o, LinkCrypto.randomBytes(32), 0) }
        }
        assertTrue("Pair them again" in stranger.message!!)

        paired(phone)
        val wrongKey = assertFailsWith<LinkException> {
            connect(phone) { i, o -> WatchLinkClient(watchId).sync(i, o, LinkCrypto.randomBytes(32), 0) }
        }
        assertTrue("no longer matches" in wrongKey.message!!)
    }

    @Test
    fun `sealed messages reject tampering and another direction`() {
        val key = LinkCrypto.randomBytes(32)
        val sealed = LinkCrypto.seal(key, byteArrayOf(1), "hello".toByteArray())
        assertEquals("hello", String(LinkCrypto.open(key, byteArrayOf(1), sealed)))
        assertFailsWith<LinkException> { LinkCrypto.open(key, byteArrayOf(2), sealed) }
        sealed[sealed.size - 1] = (sealed.last() + 1).toByte()
        assertFailsWith<LinkException> { LinkCrypto.open(key, byteArrayOf(1), sealed) }
    }

    @Test
    fun `old protocol gets an update message instead of losing basal data silently`() {
        val error = assertFailsWith<LinkException> {
            connect(FakePhone()) { i, o ->
                val link = Frames(i, o)
                link.send(message { writeInt(1); writeByte(PhoneLink.SYNC); write(watchId) })
                link.receiveOk()
            }
        }
        assertTrue("Update both" in error.message!!)
    }

    @Test
    fun `a watch that reveals another key than it committed to is refused`() {
        val phone = FakePhone()
        val committed = PairingKeyPair.generate()
        val revealed = PairingKeyPair.generate()
        val e = assertFailsWith<LinkException> {
            connect(phone) { i, o ->
                val link = Frames(i, o)
                link.send(message { writeInt(PhoneLink.VERSION); writeByte(PhoneLink.PAIR); write(watchId); writeBlob(committed.commitment) })
                link.receiveOk()
                link.send(message { writeBlob(revealed.publicKey) })
                link.receiveOk()
            }
        }
        assertTrue("does not match" in e.message!!)
        assertNull(phone.offered)
    }
}
