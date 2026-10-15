package glucowatch.core

import kotlin.test.*

class ExportCollectionTest {
    @Test fun combinedNeedsBothAndDuplicatePumpDoesNotReopenIt() {
        val collection = ExportCollection(ExportContent.SENSOR_AND_PUMP, LatestDevice.MINIMED_PUMP)
        assertEquals(ExportPortal.CARELINK, collection.next)
        assertTrue(collection.accept(ExportPortal.CARELINK))
        assertFalse(collection.ready)
        assertEquals(ExportPortal.CLARITY, collection.next)
        assertFalse(collection.accept(ExportPortal.CARELINK))
        assertEquals(ExportPortal.CLARITY, collection.next)
        collection.accept(ExportPortal.CLARITY)
        assertTrue(collection.ready); assertNull(collection.next)
    }
    @Test fun sourceIdentityAllowsOutOfOrderFilesAndRejectsUnselectedSources() {
        val collection = ExportCollection(ExportContent.SENSOR_AND_PUMP, LatestDevice.MINIMED_PUMP)
        collection.accept(ExportPortal.CLARITY)
        assertEquals(ExportPortal.CARELINK, collection.next)
        val sensor = ExportCollection(ExportContent.SENSOR, LatestDevice.MINIMED_PUMP)
        assertFailsWith<IllegalArgumentException> { sensor.accept(ExportPortal.CARELINK) }
        sensor.accept(ExportPortal.CLARITY); assertTrue(sensor.ready)
    }
}
