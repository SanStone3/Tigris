package com.leslie.tvhome

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class ShelfArtworkBudgetTest {
    @Test fun fallbackOrderIsBoundedDeduplicatedAndNeverContainsMalformedUrls() {
        val main = "https://media.example/main.jpg"
        val fallback = "https://media.example/fallback.jpg"
        assertEquals(listOf(main, fallback), ShelfLimits.artworkCandidates(main, listOf(main, "javascript:bad()", fallback)))
        assertEquals(listOf(fallback), ShelfLimits.artworkCandidates("", listOf(fallback)))
        assertEquals(3, ShelfLimits.artworkCandidates(main, (1..8).map { "https://media.example/$it.jpg" }).size)
    }

    @Test fun actualDimensionsRejectPortraitsAndSmallThumbnails() {
        assertTrue(ShelfLimits.isHdLandscape(1920, 800))
        assertTrue(ShelfLimits.isHdLandscape(1280, 720))
        assertTrue(ShelfLimits.isHdLandscape(1500, 844))
        assertFalse(ShelfLimits.isHdLandscape(1080, 607))
        assertFalse(ShelfLimits.isHdLandscape(750, 420))
        assertFalse(ShelfLimits.isHdLandscape(1495, 1662))
    }

    @Test fun decodeHasAnExactLimitBetweenPowerOfTwoSamplingSteps() {
        for ((width, height) in listOf(1921 to 1081, 2880 to 900, 3839 to 2159, 8000 to 4500)) {
            val (outW, outH) = ShelfLimits.fittedSize(width, height)
            assertTrue(outW <= 1920 && outH <= 1920)
            assertTrue(outW <= width && outH <= height)
            assertTrue(abs(outW.toDouble() / outH - width.toDouble() / height) < 0.005)
        }
        assertEquals(1920 to 800, ShelfLimits.fittedSize(1920, 800))
        assertEquals(1000 to 625, ShelfLimits.fittedSize(1000, 625))
        assertEquals(0 to 0, ShelfLimits.fittedSize(0, 1080))
    }
}
