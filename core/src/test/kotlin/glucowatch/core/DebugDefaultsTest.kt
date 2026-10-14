package glucowatch.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DebugDefaultsTest {
    @Test fun fillsAnExistingEmptyPreferenceWithoutClearingManualCredentials() {
        assertTrue(DebugDefaults.shouldApply("", null, "test-default"))
        assertTrue(DebugDefaults.shouldApply(null, null, "test-default"))
        assertFalse(DebugDefaults.shouldApply("manual", null, "test-default"))
        assertFalse(DebugDefaults.shouldApply("manual", null, ""))
    }

    @Test fun rebuildingWithAChangedDefaultUpdatesAnInstalledApp() {
        assertTrue(DebugDefaults.shouldApply("", DebugDefaults.fingerprint(""), "new-default"))
        assertTrue(DebugDefaults.shouldApply("old", DebugDefaults.fingerprint("old"), "new-default"))
        assertTrue(DebugDefaults.shouldApply("old", DebugDefaults.fingerprint("old"), ""))
    }

    @Test fun unchangedDefaultsPreserveEditsIncludingAnIntentionallyBlankToken() {
        assertFalse(DebugDefaults.shouldApply("manual", DebugDefaults.fingerprint("default"), "default"))
        assertFalse(DebugDefaults.shouldApply("", DebugDefaults.fingerprint("default"), "default"))
    }
}
