package com.leslie.tvhome

import org.junit.Assert.*
import org.junit.Test

class SystemScreensaverPolicyTest {
    private val ours = "com.leslie.tvhome/.PhotoDream"
    private val vendor = "com.vendor.saver/.Dream"
    @Test fun systemModeNeverPreemptsPositiveVendorOrSleepTimeout() {
        for (enabled in listOf(false, true)) for (timeout in listOf(null, -1L, 60_000L, 600_000L)) {
            val schedule = FrameIdlePolicy.schedule(enabled, 10, timeout, 600_000L, systemScreensaver = true)
            assertNull(schedule.delayMs)
            assertEquals(FrameIdlePolicy.Source.NOT_SCHEDULED, schedule.source)
        }
    }
    @Test fun restoresOnlyRecordedInstalledDream() {
        assertEquals(vendor, SystemScreensaverPolicy.restore(ours, vendor, "com.leslie.tvhome", setOf(vendor)))
    }
    @Test fun missingPreviousNeverGuessesSonyOrOtherVendor() {
        assertNull(SystemScreensaverPolicy.restore(ours, "", "com.leslie.tvhome", emptySet()))
        assertNull(SystemScreensaverPolicy.restore(ours, vendor, "com.leslie.tvhome", emptySet()))
    }
    @Test fun vendorChoiceIsPreserved() {
        assertNull(SystemScreensaverPolicy.restore(vendor, "old/.Dream", "com.leslie.tvhome", setOf("old/.Dream")))
    }
    @Test fun mixedSelectionKeepsOtherDreamsAndDeduplicates() {
        assertEquals(vendor, SystemScreensaverPolicy.restore("$ours:$vendor", vendor, "com.leslie.tvhome", setOf(vendor)))
        assertNull(SystemScreensaverPolicy.restore(ours, ours, "com.leslie.tvhome", setOf(ours)))
    }
}
