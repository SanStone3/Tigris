package com.leslie.tvhome

import org.junit.Assert.*
import org.junit.Test

class UiResolutionTest {
    @Test fun onlyAnActualUhdWindowCounts() {
        assertFalse(UiResolution.nativeUhd(1920, 1080))
        assertFalse(UiResolution.nativeUhd(3840, 1080))
        assertTrue(UiResolution.nativeUhd(3840, 2160))
        assertTrue(UiResolution.nativeUhd(2160, 3840))
    }
    @Test fun dockGeometryScalesToNative4k() {
        val fhd = DockLayout.tileWidth(1920, 132, 56, 5)
        val uhd = DockLayout.tileWidth(3840, 264, 112, 5)
        assertTrue(kotlin.math.abs(uhd - fhd * 2) <= 1)
        assertTrue(uhd > 0 && uhd * 5 + 112 * 4 + 264 * 2 <= 3840)
    }
}
