package com.leslie.tvhome

import org.junit.Assert.*
import org.junit.Test

class BingWallpaperPolicyTest {
    @Test fun qualityAndEffectiveAutoModeInvalidateOldQueue() {
        assertNotEquals(BingWallpaperPolicy.signature("compat", false), BingWallpaperPolicy.signature("uhd", true))
        assertNotEquals(BingWallpaperPolicy.signature("auto", false), BingWallpaperPolicy.signature("auto", true))
        assertNotEquals("", BingWallpaperPolicy.signature("compat", false))
    }

    @Test fun overlappingArchiveWindowsAndRepeatedImagesAreDeduplicated() {
        val dates = mutableSetOf<String>()
        val images = mutableSetOf<String>()
        val firstWindow = (1..8).map { "202611%02d".format(it) to "/th?id=OHR.Photo$it" }
        val secondWindow = (8..15).map { "202611%02d".format(it) to "/th?id=OHR.Photo$it" }
        val accepted = (firstWindow + secondWindow).filter { (date, base) ->
            BingWallpaperPolicy.include(date, base, dates, images)
        }
        assertEquals(15, accepted.size) // Fixture count, not a claim about Bing's daily response.
        assertFalse(BingWallpaperPolicy.include("20261116", "/th?id=OHR.Photo1", dates, images))
        assertFalse(BingWallpaperPolicy.include("20261101", "/th?id=OHR.Other", dates, images))
        assertFalse(BingWallpaperPolicy.include("", "", dates, images))
    }

    @Test fun cached1080CannotPreventAnUpgradeAndFallbackIsRetried() {
        assertFalse(BingWallpaperPolicy.canReuse("20261106.jpg", "20261106", "uhd"))
        assertFalse(BingWallpaperPolicy.canReuse("20261106-uhd.jpg", "20261106", "uhd"))
        assertFalse(BingWallpaperPolicy.canReuse("20261106-hd-1920x1080.jpg", "20261106", "uhd"))
        assertFalse(BingWallpaperPolicy.canReuse("20261106-uhd-1920x1080.jpg", "20261106", "uhd"))
        assertTrue(BingWallpaperPolicy.canReuse("20261106-uhd-3840x2160.jpg", "20261106", "uhd"))
        assertTrue(BingWallpaperPolicy.canReuse("20261106-hd-1920x1080.jpg", "20261106", "hd"))
        assertFalse(BingWallpaperPolicy.canReuse("20261105-uhd-3840x2160.jpg", "20261106", "uhd"))
    }

    @Test fun compatibilityNeverRequestsUhd() {
        val urls = BingWallpaperPolicy.candidates("/th?id=OHR.Photo", false)
        assertEquals(listOf("https://cn.bing.com/th?id=OHR.Photo_1920x1080.jpg"), urls)
    }

    @Test fun missingUhdFallsBackTo1080WithActualDimensionsInCacheName() {
        val requests = mutableListOf<String>()
        val dimensions = BingWallpaperPolicy.firstUsable(BingWallpaperPolicy.candidates("/th?id=OHR.Photo", true)) { url ->
            requests.add(url)
            if (url.endsWith("_UHD.jpg")) error("HTTP 404")
            1920 to 1080
        }
        assertEquals(2, requests.size)
        assertTrue(requests.first().endsWith("_UHD.jpg"))
        assertEquals(1920 to 1080, dimensions)
        assertEquals("20261106-uhd-1920x1080.jpg", BingWallpaperPolicy.cacheName("20261106", "uhd", dimensions!!.first, dimensions.second))
    }

    @Test fun undecodableUhdResponseAlsoFallsBack() {
        val urls = BingWallpaperPolicy.candidates("/th?id=OHR.Photo", true)
        val result = BingWallpaperPolicy.firstUsable(urls) { if (it == urls.first()) null else "decoded standard" }
        assertEquals("decoded standard", result)
        assertNull(BingWallpaperPolicy.firstUsable<String>(urls) { null })
    }

    @Test fun uhdSuccessDoesNotDownloadFallbackOrInventDimensions() {
        var requests = 0
        val actual = BingWallpaperPolicy.firstUsable(BingWallpaperPolicy.candidates("/th?id=OHR.Photo", true)) {
            requests++
            3840 to 2160
        }
        assertEquals(1, requests)
        assertEquals("20261106-uhd-3840x2160.jpg", BingWallpaperPolicy.cacheName("20261106", "uhd", actual!!.first, actual.second))
        // A server may return a smaller picture even for a UHD URL.
        assertEquals("20261106-uhd-1920x1080.jpg", BingWallpaperPolicy.cacheName("20261106", "uhd", 1920, 1080))
    }
}
