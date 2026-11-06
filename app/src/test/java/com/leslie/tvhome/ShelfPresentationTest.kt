package com.leslie.tvhome

import org.junit.Assert.*
import org.junit.Test

class ShelfPresentationTest {
    @Test fun wallpaperOverrideIsPerApp() {
        val disabled = setOf("video.one")
        assertFalse(ShelfPresentation.enabled("video.one", disabled))
        assertTrue(ShelfPresentation.enabled("video.two", disabled))
        assertTrue(ShelfPresentation.enabled("video.one", emptySet()))
    }

    @Test fun pausedRotationNeverSchedulesAnyInterval() {
        (ShelfPresentation.intervals + listOf(0, Int.MAX_VALUE, -1)).forEach {
            assertNull(ShelfPresentation.delayMs(false, it))
        }
    }

    @Test fun validIntervalsAndInvalidSavedValueHaveBoundedTimers() {
        ShelfPresentation.intervals.forEach { assertEquals(it * 1000L, ShelfPresentation.delayMs(true, it)) }
        assertEquals(8000L, ShelfPresentation.delayMs(true, -1))
    }
}
