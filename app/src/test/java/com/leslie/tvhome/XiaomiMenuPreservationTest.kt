package com.leslie.tvhome

import org.junit.Assert.*
import org.junit.Test

class XiaomiMenuPreservationTest {
    private val services = listOf("xiaomi/.Voice", "com.leslie.tvhome.redmi/com.leslie.tvhome.HomeGuardService", "reader/.Service")

    @Test fun explicitOptInAppendsOnlyTheVerifiedMenuServiceAndPreservesEveryExistingEntry() {
        assertEquals(services + XiaomiMenuPreservation.SERVICE,
            XiaomiMenuPreservation.restore(services, guard = true, xiaomi = true, optIn = true, installed = true))
    }

    @Test fun preservationIsOffByDefaultAndDoesNotRemoveAnExistingMenuService() {
        assertSame(services, XiaomiMenuPreservation.restore(services, guard = true, xiaomi = true, installed = true))
        val existing = services + XiaomiMenuPreservation.SERVICE
        assertSame(existing, XiaomiMenuPreservation.restore(existing, guard = true, xiaomi = true, installed = true))
    }

    @Test fun guardDisabledNeverRestoresTheMenuServiceEvenWithOptIn() {
        assertSame(services, XiaomiMenuPreservation.restore(services, guard = false, xiaomi = true, optIn = true, installed = true))
    }

    @Test fun otherVendorsAndAnAbsentOrDisabledServiceAreLeftAlone() {
        assertSame(services, XiaomiMenuPreservation.restore(services, guard = true, xiaomi = false, optIn = true, installed = true))
        assertSame(services, XiaomiMenuPreservation.restore(services, guard = true, xiaomi = true, optIn = true, installed = false))
    }

    @Test fun fullAndShortComponentNamesAreIdempotentWithoutRewritingOtherServices() {
        for (name in listOf(XiaomiMenuPreservation.SERVICE,
            XiaomiMenuPreservation.PACKAGE + "/.system.accessibility.MyAccessibilityService")) {
            val existing = services + name
            assertSame(existing, XiaomiMenuPreservation.restore(existing, guard = true, xiaomi = true, optIn = true, installed = true))
        }
    }
}
