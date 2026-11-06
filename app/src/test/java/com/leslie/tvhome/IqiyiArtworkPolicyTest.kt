package com.leslie.tvhome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IqiyiArtworkPolicyTest {
    private val cover = "https://pic6.iqiyipic.com/image/20261102/1e/06/a_100692840_m_601_m8_260_360.jpg"
    private val landscape = "https://pic6.iqiyipic.com/image/20261102/1e/06/a_100692840_m_601_m8_1920_1080.jpg"

    @Test fun usesTheDocumentedLandscapeRenditionForObservedProgrammeCovers() {
        assertEquals(landscape, IqiyiArtworkPolicy.landscapeCandidate(cover))
        assertEquals(landscape, IqiyiArtworkPolicy.landscapeCandidate(cover.removePrefix("https:")))
        assertEquals(landscape, IqiyiArtworkPolicy.landscapeCandidate(cover.replace("https:", "http:")))
        assertEquals(landscape, IqiyiArtworkPolicy.landscapeCandidate(landscape))
        assertEquals(landscape.replace(".jpg", ".jpeg"), IqiyiArtworkPolicy.landscapeCandidate(cover.replace(".jpg", ".jpeg")))
        assertEquals("https://pic5.iqiyipic.com/image/20261009/f0/68/v_123_m_601_1920_1080.jpg",
            IqiyiArtworkPolicy.landscapeCandidate("https://pic5.iqiyipic.com/image/20261009/f0/68/v_123_m_601_260_360.jpg"))
    }

    @Test fun doesNotGuessUnknownPostersOrAppendDimensionsToAnUnsizedImage() {
        for (url in listOf(
            cover.replace("_260_360", ""),
            cover.replace("_m_601", "_m_600"),
            cover.replace("a_100692840", "p_100692840"),
            cover.replace(".jpg", ".png"),
            cover.replace("_260_360", "_0_360"),
        )) assertNull(url, IqiyiArtworkPolicy.landscapeCandidate(url))
    }

    @Test fun rejectsNonOfficialOrAmbiguousImageUrls() {
        for (url in listOf(
            cover.replace("pic6.iqiyipic.com", "images.example.com"),
            cover.replace("pic6.iqiyipic.com", "pic6.iqiyipic.com.evil.example"),
            cover.replace("pic6.iqiyipic.com", "fake-iqiyipic.com"),
            cover.replace("pic6.iqiyipic.com", "pic6.iqiyipic.com@evil.example"),
            cover.replace("pic6.iqiyipic.com", "user@pic6.iqiyipic.com"),
            cover.replace("pic6.iqiyipic.com", "pic6.iqiyipic.com:443"),
            "$cover?width=1920", "$cover#poster",
            cover.replace("/image/", "/poster/"),
            cover.replace("/image/", "/image/../image/"),
            cover.replace("/image/", "/image/%2e%2e/image/"),
            cover.replace("/1e/", "//"),
            cover.replace("a_100692840", "a_10069 2840"),
            cover.replace("https:", "javascript:"), "file:///tmp/image_260_360.jpg", "",
        )) assertNull(url, IqiyiArtworkPolicy.landscapeCandidate(url))
    }

    @Test fun onlyKnownLandscapeCandidatesRequireTheExtraBitmapCheck() {
        assertTrue(IqiyiArtworkPolicy.isLandscapeCandidate(landscape))
        assertFalse(IqiyiArtworkPolicy.isLandscapeCandidate(cover))
        assertFalse(IqiyiArtworkPolicy.isLandscapeCandidate("https://media.example/film_1920_1080.jpg"))
    }

    @Test fun realPixelsMustBeLandscapeAndAtLeast720p() {
        assertTrue(IqiyiArtworkPolicy.acceptsDimensions(1920, 1080))
        assertTrue(IqiyiArtworkPolicy.acceptsDimensions(1280, 720))
        for ((width, height) in listOf(260 to 360, 1080 to 1920, 1920 to 1920, 750 to 420, 1279 to 720, 1920 to 719, 0 to 0, -1 to 1080)) {
            assertFalse("$width x $height", IqiyiArtworkPolicy.acceptsDimensions(width, height))
        }
    }
}
