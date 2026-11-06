package com.leslie.tvhome

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ShelfFeedPreviewTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = 1_800_000_000_000L
    private fun item(id: String) = Shelf.Item(id, "meta", "https://art.example/$id.jpg",
        listOf("https://art.example/$id-fallback.jpg"), requireFullHdArtwork = true)
    private fun file(source: String) = File(temporary.root, "feed-$source-v${ShelfFeedPreview.VERSION}.json")

    @Test fun recreatedProcessCanReadMetadataAndSelectAnExistingFullHdPictureWithoutNetwork() {
        val items = listOf(item("missing"), item("cached"))
        assertTrue(ShelfFeedPreview.write(temporary.root, "youku", ShelfFeedPreview.Snapshot(now, items)))
        // No in-memory feed survives; only metadata and previously verified local artwork remain.
        val restored = ShelfFeedPreview.read(temporary.root, "youku", now + 1000)!!
        val cachedPicture = Any()
        val localPictures = mapOf(items[1].image to cachedPicture)
        val tried = ArrayList<String>()
        val selected = ShelfCandidateScan.first(restored.items) { programme ->
            assertTrue(programme.requireFullHdArtwork)
            tried += programme.image
            localPictures[programme.image]
        }
        assertEquals(1, selected!!.index)
        assertSame(cachedPicture, selected.value)
        assertEquals(items.map { it.image }, tried)
        assertEquals(items, restored.items)
    }

    @Test fun evenARecentDiskPreviewCannotDelayTheOnlineRefreshAndAnExceptionKeepsItsItems() {
        val snapshot = ShelfFeedPreview.Snapshot(now - 1000, listOf(item("old")))
        val stale = ShelfFeedPreview.staleFeed(snapshot, now)
        assertEquals(0L, ShelfRefreshPolicy.feedDelayMs(stale, now))
        val offline = ShelfRefreshPolicy.afterFetch(stale, null, now)
        assertSame(snapshot.items, offline.items)
        assertEquals(ShelfRefreshPolicy.RETRY_MS, ShelfRefreshPolicy.feedDelayMs(offline, now))
        val online = ShelfRefreshPolicy.afterFetch(offline, listOf(item("new")), now + 1000)
        assertEquals("new", online.items.single().title)
        assertEquals(ShelfRefreshPolicy.TTL_MS, ShelfRefreshPolicy.feedDelayMs(online, now + 1000))
    }

    @Test fun validOnlineReplacementIsAtomicAndACancelledWriteLeavesThePreviousPreviewIntact() {
        val old = ShelfFeedPreview.Snapshot(now, listOf(item("old")))
        assertTrue(ShelfFeedPreview.write(temporary.root, "bilibili", old))
        var checks = 0
        assertFalse(ShelfFeedPreview.write(temporary.root, "bilibili",
            ShelfFeedPreview.Snapshot(now + 1, listOf(item("cancelled")))) { ++checks < 3 })
        assertEquals(old, ShelfFeedPreview.read(temporary.root, "bilibili", now + 2))
        assertFalse(temporary.root.listFiles()!!.any { it.name.endsWith(".part") })
        val replacement = ShelfFeedPreview.Snapshot(now + 3, listOf(item("new")))
        assertTrue(ShelfFeedPreview.write(temporary.root, "bilibili", replacement))
        assertEquals(replacement, ShelfFeedPreview.read(temporary.root, "bilibili", now + 4))
    }

    @Test fun brokenOversizeWrongVersionAndWrongSourceMetadataFallBackToOnline() {
        file("youku").writeText("{truncated")
        assertNull(ShelfFeedPreview.read(temporary.root, "youku", now))
        file("youku").writeBytes(ByteArray(ShelfFeedPreview.MAX_BYTES + 1))
        assertNull(ShelfFeedPreview.read(temporary.root, "youku", now))
        assertTrue(ShelfFeedPreview.write(temporary.root, "youku", ShelfFeedPreview.Snapshot(now, listOf(item("good")))))
        val json = JSONObject(file("youku").readText())
        file("youku").writeText(json.put("version", 99).toString())
        assertNull(ShelfFeedPreview.read(temporary.root, "youku", now))
        file("youku").writeText(json.put("version", ShelfFeedPreview.VERSION).put("source", "bilibili").toString())
        assertNull(ShelfFeedPreview.read(temporary.root, "youku", now))
    }

    @Test fun stalePreviewExpiresAfterSevenDaysAndFutureTimestampsAreRejected() {
        assertTrue(ShelfFeedPreview.write(temporary.root, "youku", ShelfFeedPreview.Snapshot(now, listOf(item("good")))))
        assertNotNull(ShelfFeedPreview.read(temporary.root, "youku", now + ShelfFeedPreview.MAX_AGE_MS))
        assertNull(ShelfFeedPreview.read(temporary.root, "youku", now + ShelfFeedPreview.MAX_AGE_MS + 1))
        assertNull(ShelfFeedPreview.read(temporary.root, "youku", now - 1))
    }

    @Test fun onlyBuiltInBilibiliAndYoukuPersistAndAssociatedCustomFeedsNeverPreviewThem() {
        assertEquals("bilibili", ShelfFeedPreview.eligibleSource("bilibili", custom = false))
        assertEquals("youku", ShelfFeedPreview.eligibleSource("youku", custom = false))
        for (source in listOf("bilibili", "youku")) {
            assertNull(ShelfFeedPreview.eligibleSource(source, custom = true))
        }
        for (source in listOf("mango", "custom", "../youku", "iqiyi")) {
            assertFalse(ShelfFeedPreview.write(temporary.root, source, ShelfFeedPreview.Snapshot(now, listOf(item("private")))))
            assertNull(ShelfFeedPreview.read(temporary.root, source, now))
        }
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun metadataIsBoundedToTwelveFullHdProgrammesAndCancellationRejectsReads() {
        assertTrue(ShelfFeedPreview.write(temporary.root, "youku", ShelfFeedPreview.Snapshot(now, (1..30).map { item("$it") })))
        assertEquals(ShelfLimits.MAX_ITEMS, ShelfFeedPreview.read(temporary.root, "youku", now)!!.items.size)
        assertTrue(file("youku").length() <= ShelfFeedPreview.MAX_BYTES)
        assertNull(ShelfFeedPreview.read(temporary.root, "youku", now) { false })
        assertFalse(ShelfFeedPreview.write(temporary.root, "youku",
            ShelfFeedPreview.Snapshot(now, listOf(Shelf.Item("low", "", "https://art.example/low.jpg")))))
    }
}
