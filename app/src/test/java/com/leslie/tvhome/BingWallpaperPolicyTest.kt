package com.leslie.tvhome

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.json.JSONArray
import org.json.JSONObject
import java.awt.image.BufferedImage
import java.io.File
import java.time.Instant
import java.time.ZoneId
import javax.imageio.ImageIO

class BingWallpaperPolicyTest {
    @get:Rule val temporary = TemporaryFolder()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-11-07T01:00:00Z").toEpochMilli()
    private fun archiveImage(date: String, full: String, id: String) = JSONObject()
        .put("startdate", date).put("fullstartdate", full).put("urlbase", "/th?id=OHR.$id")
        .put("title", "Title $id").put("copyright", "Photographer $id")

    private fun picture(name: String): File = File(temporary.root, name).also {
        assertTrue(ImageIO.write(BufferedImage(16, 9, BufferedImage.TYPE_INT_RGB), "jpg", it))
    }

    @Test fun qualityAndEffectiveAutoModeInvalidateOldQueue() {
        assertNotEquals(BingWallpaperPolicy.signature("compat", false), BingWallpaperPolicy.signature("uhd", true))
        assertNotEquals(BingWallpaperPolicy.signature("auto", false), BingWallpaperPolicy.signature("auto", true))
        assertNotEquals("", BingWallpaperPolicy.signature("compat", false))
    }

    @Test fun currentSelectionAndRecentHistoryAreRequestedFromThePreferredChineseMarket() {
        val urls = BingWallpaperPolicy.archiveUrls("20261107")
        assertTrue(urls.first().startsWith("https://cn.bing.com/"))
        assertTrue(urls.all { it.contains("idx=0&n=8&mkt=zh-CN") && it.contains("day=20261107") })
        assertTrue(urls.none { it.contains("idx=7") })
    }

    @Test fun currentSelectionUsesItsLocalPublicationDateAndAlwaysPrecedesDeduplicatedHistory() {
        // A real CN response on Nov 7 uses Nov 6's startdate and its 16:00 UTC publication instant.
        val today = archiveImage("20261106", "202611061600", "Today")
        val yesterday = archiveImage("20261105", "202611051600", "Yesterday")
        val text = JSONObject().put("images", JSONArray().put(yesterday).put(today).put(today)
            .put(archiveImage("20261105", "202611051600", "DuplicateDate"))
            .put(archiveImage("20261104", "202611041600", "Yesterday"))).toString()
        val selected = BingWallpaperPolicy.archive(text, now, zone)
        assertEquals(listOf("20261106", "20261105"), selected.map { it.date })
        assertEquals("/th?id=OHR.Today", selected.first().base)
        assertEquals("Title Today — Photographer Today", selected.first().caption)
        assertTrue(BingWallpaperPolicy.archive(JSONObject().put("images", JSONArray().put(yesterday)).toString(), now, zone).isEmpty())
        assertTrue(BingWallpaperPolicy.archive(JSONObject().put("images", JSONArray()
            .put(archiveImage("20261106/../other", "202611061600", "BadDate"))).toString(), now, zone).isEmpty())
        assertTrue(BingWallpaperPolicy.archive("{broken", now, zone).isEmpty())
    }

    @Test fun localMidnightInvalidatesYesterdayEvenWhenItsQueueWasMadeSecondsAgo() {
        val before = Instant.parse("2026-11-06T15:59:50Z").toEpochMilli()
        val after = before + 20_000
        assertEquals("20261106", BingWallpaperPolicy.sourceDay(before, zone))
        assertEquals("20261107", BingWallpaperPolicy.sourceDay(after, zone))
        assertTrue(BingWallpaperPolicy.isToday("20261106", before, zone))
        assertFalse(BingWallpaperPolicy.isToday("20261106", after, zone))
        assertFalse(BingWallpaperPolicy.isToday(null, after, zone))
        assertFalse(BingWallpaperPolicy.isToday("", after, zone))
        assertNotEquals("bing-v2:compat:hd", BingWallpaperPolicy.signature("compat", false))
        assertNotEquals("bing-daily-v3:compat:hd", BingWallpaperPolicy.signature("compat", false))
    }

    @Test fun refreshThenForcedAdvanceOrPhotoFrameNextConsumesTodayBeforeHistoryOnlyOnce() {
        val queue = JSONObject().put("sourceDay", BingWallpaperPolicy.sourceDay(now))
            .put("todayReady", false).put("firstPending", false)
            .put("items", JSONArray().put("today").put("history"))
        assertFalse(BingWallpaperPolicy.ready(queue, now))
        assertFalse(BingWallpaperPolicy.consumePriority(queue, now))
        queue.put("todayReady", true).put("firstPending", true)
        assertTrue(BingWallpaperPolicy.consumePriority(queue, now))
        assertFalse(BingWallpaperPolicy.consumePriority(queue, now))
        assertTrue(BingWallpaperPolicy.ready(queue, now))
        assertFalse(BingWallpaperPolicy.ready(queue, now + 86_400_000L))
        assertEquals(2, queue.getJSONArray("items").length())
    }

    @Test fun failedDownloadOrUndecodableReplacementKeepsTheOldShowingPictureAndCaption() {
        val old = picture("yesterday.jpg")
        val meta = JSONObject().put(old.name, "Yesterday")
        File(temporary.root, "showing").writeText(old.name)
        File(temporary.root, "meta.json").writeText(meta.toString())
        File(temporary.root, "slot").writeText("existing-slot")
        val failed = BingWallpaperPolicy.firstUsable<File>(listOf("new-uhd", "new-hd")) { error("network unavailable") }
        assertFalse(BingWallpaperPolicy.publish(temporary.root, failed, meta, now) { ImageIO.read(it) != null })
        val corrupt = File(temporary.root, "today.jpg").also { it.writeText("not an image") }
        assertFalse(BingWallpaperPolicy.publish(temporary.root, corrupt, meta, now) { ImageIO.read(it) != null })
        assertTrue(old.exists())
        assertEquals(old.name, File(temporary.root, "showing").readText())
        assertEquals("Yesterday", JSONObject(File(temporary.root, "meta.json").readText()).getString(old.name))
        assertEquals("existing-slot", File(temporary.root, "slot").readText())
    }

    @Test fun verifiedNewJpegBecomesCurrentKeepsRecentHistoryAndRemovesExpiredCaptions() {
        val old = picture("20261105-hd-1920x1080.jpg")
        val older = picture("20261001-hd-1920x1080.jpg")
        val today = picture("20261106-hd-1920x1080.jpg")
        val part = File(temporary.root, "interrupted.jpg.part").also { it.writeText("partial") }
        val meta = JSONObject().put(old.name, "Yesterday").put(older.name, "Archive").put(today.name, "Today")
        File(temporary.root, "showing").writeText(old.name)
        File(temporary.root, "slot").writeText("old-slot")
        assertTrue(BingWallpaperPolicy.publish(temporary.root, today, meta, now) { ImageIO.read(it) != null })
        assertTrue(old.exists())
        assertFalse(older.exists())
        assertTrue(today.exists())
        assertTrue(part.exists())
        assertEquals(setOf(today.name, old.name), temporary.root.listFiles()!!.filter { it.extension == "jpg" }.map { it.name }.toSet())
        assertEquals(today.name, File(temporary.root, "showing").readText())
        val storedMeta = JSONObject(File(temporary.root, "meta.json").readText())
        assertEquals(setOf(today.name, old.name), storedMeta.keys().asSequence().toSet())
        assertEquals("Today", storedMeta.getString(today.name))
        assertEquals("old-slot", File(temporary.root, "slot").readText())
        // Caching the next history candidate must not replace today's foreground selection.
        assertTrue(BingWallpaperPolicy.publish(temporary.root, old, meta, now, makeCurrent = false) { ImageIO.read(it) != null })
        assertEquals(today.name, File(temporary.root, "showing").readText())
    }

    @Test fun dateWindowAndQualityDedupUseRealDatesRatherThanRecentDownloadTimes() {
        val hd = BingWallpaperPolicy.Cached("20261106-hd-1920x1080.jpg", now + 10)
        val uhd = BingWallpaperPolicy.Cached("20261106-uhd-3840x2160.jpg", now - 100)
        val expired = BingWallpaperPolicy.Cached("20261001-hd-1920x1080.jpg", now + 1000)
        assertEquals(setOf(hd.name, expired.name), BingWallpaperPolicy.evictions(listOf(hd, uhd, expired), emptySet(), now, zone))
        // Until a replacement is usable, an old/current quality variant keeps priority.
        assertEquals(setOf(uhd.name), BingWallpaperPolicy.evictions(listOf(hd, uhd), setOf(hd.name), now, zone))
        assertFalse(expired.name in BingWallpaperPolicy.evictions(listOf(expired), setOf(expired.name), now, zone))
    }

    @Test fun aValidatedSameDateQualityChangeKeepsTheNewFileReadyForDisplay() {
        val uhd = picture("20261106-uhd-3840x2160.jpg")
        val hd = picture("20261106-hd-1920x1080.jpg")
        File(temporary.root, "showing").writeText(uhd.name)
        val meta = JSONObject().put(uhd.name, "UHD").put(hd.name, "HD")
        assertTrue(BingWallpaperPolicy.publish(temporary.root, hd, meta, now, makeCurrent = false) { ImageIO.read(it) != null })
        assertTrue(hd.exists())
        assertFalse(uhd.exists())
        assertEquals(hd.name, File(temporary.root, "showing").readText())
    }

    @Test fun twelveRealDatesKeepAtMostTenAndEvictOldDatesDespiteTheirNewerDownloadTimes() {
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val entries = (1L..12L).map { age -> BingWallpaperPolicy.Cached(
            date.minusDays(age).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) + "-hd-1920x1080.jpg", now + age * 1000)
        }
        val current = entries.last().name
        val evicted = BingWallpaperPolicy.evictions(entries, setOf(current), now, zone)
        assertEquals(10, entries.count { it.name !in evicted })
        assertFalse(current in evicted)
        assertEquals(setOf(entries[9].name, entries[10].name), evicted)
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
