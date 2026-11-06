package com.leslie.tvhome

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class ShelfRuntimePolicyTest {
    private data class Programme(val id: String, val images: List<String>)
    private data class Picture(val width: Int, val height: Int)

    /** A fake transport has real missing, low-resolution and valid responses; every GET is recorded. */
    private class ArtworkServer(private val responses: Map<String, Picture?>) {
        val downloads = ArrayList<String>()
        var active = true
        fun picture(item: Programme): Picture? {
            val urls = ShelfLimits.artworkCandidates(item.images.firstOrNull().orEmpty(), item.images.drop(1))
            for (url in urls) {
                if (!active) return null
                downloads.add(url)
                val picture = responses[url] ?: continue
                if (ShelfLimits.isFullHdLandscape(picture.width, picture.height)) return picture
            }
            return null
        }
    }

    private fun programme(id: String, vararg suffixes: String) = Programme(id,
        (if (suffixes.isEmpty()) arrayOf(id) else suffixes).map { "https://art.example/$it.jpg" })
    private fun url(id: String) = "https://art.example/$id.jpg"
    private val fullHd = Picture(1920, 1080)

    @Test fun lowResolutionFirstProgrammeDoesNotHideTheRestOfTheFeed() {
        val server = ArtworkServer(mapOf(url("low") to Picture(1500, 844), url("good") to fullHd))
        val selected = ShelfCandidateScan.first(listOf(programme("low"), programme("good")), load = server::picture)
        assertEquals(1, selected!!.index)
        assertEquals(fullHd, selected.value)
        assertEquals(listOf(url("low"), url("good")), server.downloads)
    }

    @Test fun rotationSkipsABrokenMiddleProgrammeInOnePass() {
        val server = ArtworkServer(mapOf(url("a") to fullHd, url("c") to fullHd))
        val selected = ShelfCandidateScan.first(listOf(programme("a"), programme("broken"), programme("c")),
            start = 1, limit = 2, load = server::picture)
        assertEquals(2, selected!!.index)
        assertEquals(listOf(url("broken"), url("c")), server.downloads)
    }

    @Test fun rotationWrapsAndNeverRetriesTheCurrentPicture() {
        val server = ArtworkServer(mapOf(url("a") to fullHd, url("b") to fullHd))
        val selected = ShelfCandidateScan.first(listOf(programme("a"), programme("b"), programme("broken")),
            start = 2, limit = 2, load = server::picture)
        assertEquals(0, selected!!.index)
        assertEquals(listOf(url("broken"), url("a")), server.downloads)
        assertFalse(url("b") in server.downloads)
    }

    @Test fun allUnqualifiedPicturesEndOneBoundedPass() {
        val server = ArtworkServer(mapOf(url("a") to Picture(1280, 720), url("b") to Picture(1920, 800)))
        assertNull(ShelfCandidateScan.first(listOf(programme("a"), programme("b")), start = 1, load = server::picture))
        assertEquals(listOf(url("b"), url("a")), server.downloads)
    }

    @Test fun eachProgrammeTriesOnlyThreeDistinctPictures() {
        val server = ArtworkServer(mapOf(url("four") to fullHd))
        assertNull(ShelfCandidateScan.first(listOf(programme("a", "one", "one", "two", "three", "four")), load = server::picture))
        assertEquals(listOf(url("one"), url("two"), url("three")), server.downloads)
    }

    @Test fun candidateListsLargerThanTheFeedBudgetDoNotCauseMoreDownloads() {
        val server = ArtworkServer(emptyMap())
        assertNull(ShelfCandidateScan.first((0..99).map { programme("$it") }, load = server::picture))
        assertEquals(30, server.downloads.size)
        assertEquals(30, server.downloads.distinct().size)
    }

    @Test fun cancellingAfterAFailedPictureStopsBeforeTheNextDownload() {
        val session = ShelfSession()
        val ticket = session.begin("dock")
        val server = ArtworkServer(emptyMap())
        assertNull(ShelfCandidateScan.first(listOf(programme("a"), programme("b")),
            isCurrent = { session.accepts(ticket) }) {
            server.picture(it).also { session.invalidate() }
        })
        assertEquals(listOf(url("a")), server.downloads)
    }

    @Test fun cancellingBeforeTheFirstCandidateDownloadsNothing() {
        val server = ArtworkServer(emptyMap())
        assertNull(ShelfCandidateScan.first(listOf(programme("a")), isCurrent = { false }, load = server::picture))
        assertTrue(server.downloads.isEmpty())
    }

    @Test fun cancellationDiscardsACompletedPictureAndStopsTheScan() {
        var active = true
        val discarded = ArrayList<Picture>()
        assertNull(ShelfCandidateScan.first(listOf("one", "two"), isCurrent = { active }, discard = discarded::add) {
            active = false
            fullHd
        })
        assertEquals(listOf(fullHd), discarded)
    }

    @Test fun cancellationAlsoStopsReadingAnInFlightResponse() {
        var current = true
        val input = object : ByteArrayInputStream(ByteArray(20_000)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val read = super.read(b, off, len)
                current = false
                return read
            }
        }
        assertThrows(IOException::class.java) { ShelfLimits.readAtMost(input, 20_000) { current } }
        assertEquals(20_000 - 8192, input.available())
    }

    @Test fun pausedOrRefocusedSessionsRejectThePreviousFeedResult() {
        val session = ShelfSession()
        val old = session.begin("a")
        session.invalidate()
        val resumed = session.begin("a")
        assertFalse(session.accepts(old))
        assertTrue(session.accepts(resumed))
        session.begin("b")
        assertFalse(session.accepts(resumed))
    }

    @Test fun anUnchangedProgrammeSurvivesAFeedRefreshWithRotationDisabled() {
        val current = programme("current")
        val reordered = listOf(programme("new"), current)
        assertEquals(1, ShelfRefreshPolicy.retainedIndex(reordered, current) { a, b -> a.id == b.id })
        assertEquals(-1, ShelfRefreshPolicy.retainedIndex(listOf(programme("new")), current) { a, b -> a.id == b.id })
        assertEquals(-1, ShelfRefreshPolicy.retainedIndex(reordered, null) { a, b -> a.id == b.id })
    }

    @Test fun foregroundRefreshUsesRemainingCacheTimeAndRetriesAfterAnEmptyResult() {
        val at = 100_000L
        assertEquals(0L, ShelfRefreshPolicy.delayMs(null, at))
        assertEquals(600_000L, ShelfRefreshPolicy.delayMs(at, at + 300_000))
        assertEquals(0L, ShelfRefreshPolicy.delayMs(at, at + ShelfRefreshPolicy.TTL_MS))
        // A failed or empty feed receives a cache timestamp too; resuming cannot hammer it.
        val attemptedAt = at + ShelfRefreshPolicy.TTL_MS
        assertEquals(ShelfRefreshPolicy.TTL_MS, ShelfRefreshPolicy.delayMs(attemptedAt, attemptedAt))
        assertEquals(ShelfRefreshPolicy.TTL_MS, ShelfRefreshPolicy.delayMs(attemptedAt, at))
    }

    @Test fun provenLowResolutionFailuresExpireAndDoNotPoisonOtherQualityPolicies() {
        val rejected = ShelfArtworkRejections()
        rejected.add("full:a", 1000)
        assertTrue(rejected.contains("full:a", 1001))
        assertFalse(rejected.contains("legacy:a", 1001))
        assertFalse(rejected.contains("full:a", 1000 + ShelfRefreshPolicy.TTL_MS))
    }

    @Test fun rejectedArtworkCacheHasABoundedNumberOfUrls() {
        val rejected = ShelfArtworkRejections(2)
        rejected.add("old", 10)
        rejected.add("new", 11)
        rejected.add("newest", 12)
        assertFalse(rejected.contains("old", 12))
        assertTrue(rejected.contains("new", 12))
        assertTrue(rejected.contains("newest", 12))
    }

    @Test fun fullHdRequiresNativePixelsAndANarrowSixteenToNineTolerance() {
        assertTrue(ShelfLimits.isFullHdLandscape(1920, 1080))
        assertTrue(ShelfLimits.isFullHdLandscape(3840, 2160))
        assertTrue(ShelfLimits.isFullHdLandscape(1920, 1086))
        for ((w, h) in listOf(1500 to 844, 1280 to 720, 1920 to 800, 1920 to 1087, 1080 to 1920, 1919 to 1080, 1920 to 1079)) {
            assertFalse("$w x $h", ShelfLimits.isFullHdLandscape(w, h))
        }
        assertFalse(ShelfLimits.isFullHdLandscape(1920, 1072)) // Cropping bars cannot leave a sub-Full-HD picture.
    }

    @Test fun onlyExplicitFullHdItemsGetTheLargerDownloadBudget() {
        assertEquals(2 * 1024 * 1024, ShelfLimits.imageBytes(false))
        assertEquals(5 * 1024 * 1024, ShelfLimits.imageBytes(true))
        val legacy = Shelf.Item("legacy", "", url("legacy"), requireHdArtwork = true)
        assertFalse(legacy.requireFullHdArtwork)
        assertEquals(2 * 1024 * 1024, ShelfLimits.imageBytes(legacy.requireFullHdArtwork))
    }

    @Test fun cacheRetainsAtMostSixtyOfTheNewestFiles() {
        val entries = (0..69).map { ShelfDiskBudget.Entry("$it", 1_000, it.toLong()) }
        assertEquals((0..9).map { "$it" }.toSet(), ShelfDiskBudget.evictions(entries).toSet())
    }

    @Test fun largerFullHdFilesAlsoRespectTheTotalDiskBudget() {
        val entries = (0..59).map { ShelfDiskBudget.Entry("$it", 5L * 1024 * 1024, it.toLong()) }
        val evicted = ShelfDiskBudget.evictions(entries).toSet()
        val kept = entries.filter { it.name !in evicted }
        assertEquals(24, kept.size)
        assertEquals(ShelfDiskBudget.MAX_BYTES, kept.sumOf { it.bytes })
        assertEquals((36..59).map { "$it" }.toSet(), kept.map { it.name }.toSet())
    }

    @Test fun anOversizeCacheEntryCannotOverflowOrEvictEveryUsableFile() {
        val entries = listOf(ShelfDiskBudget.Entry("huge", Long.MAX_VALUE, 3),
            ShelfDiskBudget.Entry("new", ShelfDiskBudget.MAX_BYTES, 2), ShelfDiskBudget.Entry("old", 1, 1))
        assertEquals(setOf("huge", "old"), ShelfDiskBudget.evictions(entries).toSet())
    }
}
