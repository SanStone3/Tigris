package com.leslie.tvhome

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.ZoneId

class CacheMaintenanceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = 1_800_000_000_000L
    private val day = CacheMaintenancePolicy.UNUSED_MS

    private fun file(path: String, usedAt: Long = now, bytes: Long = 16): File =
        File(temporary.root, path).also {
            it.parentFile!!.mkdirs()
            RandomAccessFile(it, "rw").use { output -> output.setLength(bytes) }
            assertTrue(it.setLastModified(usedAt))
        }

    private fun item(id: String) = Shelf.Item(id, "", "https://art.example/$id.jpg",
        listOf("https://backup.example/$id.jpg"), requireFullHdArtwork = true)

    @Test fun dailyGateRunsAtTheNextLocalDayEvenUnderAMinuteAndHandlesClockRollback() {
        val zone = ZoneId.of("Asia/Shanghai")
        val beforeMidnight = Instant.parse("2026-11-06T15:59:50Z").toEpochMilli()
        assertTrue(CacheMaintenancePolicy.due(0, beforeMidnight, zone))
        assertFalse(CacheMaintenancePolicy.due(beforeMidnight, beforeMidnight + 5_000, zone))
        assertTrue(CacheMaintenancePolicy.due(beforeMidnight, beforeMidnight + 20_000, zone))
        assertTrue(CacheMaintenancePolicy.due(beforeMidnight, beforeMidnight - 1, zone))
        assertFalse(CacheMaintenancePolicy.expired(now - day + 1, now))
        assertTrue(CacheMaintenancePolicy.expired(now - day, now))
        assertFalse(CacheMaintenancePolicy.expired(now + 1, now))
    }

    @Test fun dailyShelfCleanupKeepsOnlyTwoLocalPreviewPicturesPerValidSourceAndFreshUses() {
        val shelf = File(temporary.root, "cache/shelf")
        val keep = mutableSetOf<String>()
        for (source in ShelfFeedPreview.SOURCES) {
            val items = (1..4).map { item("$source-$it") }
            assertTrue(ShelfFeedPreview.write(shelf, source, ShelfFeedPreview.Snapshot(now - 3 * day, items)))
            // A missing primary still preserves an existing validated fallback.
            items.forEachIndexed { index, item ->
                val url = if (index == 0) item.fallbackImages.single() else item.image
                val name = ShelfDiskBudget.fileName(url)
                file("cache/shelf/$name", now - 2 * day + if (index < 2) 100 - index else 0)
                if (index < 2) keep += name
            }
        }
        val fresh = file("cache/shelf/fresh.jpg", now - day + 1)
        val old = file("cache/shelf/old.jpg", now - day)
        val freshPart = file("cache/shelf/live.jpg.part", now - day + 1)
        val oldPart = file("cache/shelf/interrupted.jpg.part", now - day)
        val obsolete = file("cache/shelf/feed-youku-v0.json", now)
        val untouched = file("cache/unrelated-cache.jpg", now - 2 * day)
        val nested = file("cache/shelf/nested/old.jpg", now - 2 * day)

        CacheMaintenanceFiles.cleanShelf(shelf, now)

        assertEquals(keep + fresh.name, shelf.listFiles()!!.filter { it.extension == "jpg" }.map { it.name }.toSet())
        assertFalse(old.exists())
        assertFalse(oldPart.exists())
        assertFalse(obsolete.exists())
        assertTrue(freshPart.exists())
        assertTrue(untouched.exists())
        assertTrue(nested.exists())
        for (source in ShelfFeedPreview.SOURCES) assertNotNull(ShelfFeedPreview.read(shelf, source, now))
    }

    @Test fun expiredOrBrokenFeedCannotKeepOldPicturesAlive() {
        val shelf = File(temporary.root, "shelf")
        val item = item("expired")
        assertTrue(ShelfFeedPreview.write(shelf, "youku", ShelfFeedPreview.Snapshot(now - ShelfFeedPreview.MAX_AGE_MS - 1, listOf(item))))
        val picture = file("shelf/${ShelfDiskBudget.fileName(item.image)}", now - 2 * day)
        val broken = file("shelf/feed-bilibili-v1.json").also { it.writeText("{truncated") }
        CacheMaintenanceFiles.cleanShelf(shelf, now)
        assertFalse(picture.exists())
        assertFalse(broken.exists())
        assertFalse(File(shelf, "feed-youku-v1.json").exists())
    }

    @Test fun previewProtectionUsesRecentActualArtworkAcrossAllFallbacksRatherThanProgrammeRank() {
        val shelf = File(temporary.root, "shelf")
        val items = (1..3).map { item("rank-$it") }
        assertTrue(ShelfFeedPreview.write(shelf, "bilibili", ShelfFeedPreview.Snapshot(now - day, items)))
        val lowRankedFirst = file("shelf/${ShelfDiskBudget.fileName(items[0].image)}", now - 4 * day)
        val lowRankedSecond = file("shelf/${ShelfDiskBudget.fileName(items[1].image)}", now - 3 * day)
        val actualDisplayed = file("shelf/${ShelfDiskBudget.fileName(items[2].image)}", now - 2 * day + 100)
        val actualFallback = file("shelf/${ShelfDiskBudget.fileName(items[2].fallbackImages.single())}", now - 2 * day + 99)

        CacheMaintenanceFiles.cleanShelf(shelf, now)

        assertTrue(actualDisplayed.exists())
        assertTrue(actualFallback.exists())
        assertFalse(lowRankedFirst.exists())
        assertFalse(lowRankedSecond.exists())
        assertEquals(setOf(actualDisplayed.name, actualFallback.name), CacheMaintenanceFiles.shelfPreviewImages(shelf, now))
    }

    @Test fun shelfPreviewProtectionStillFitsTheRealDiskBudget() {
        val shelf = File(temporary.root, "shelf")
        val item = item("protected")
        assertTrue(ShelfFeedPreview.write(shelf, "youku", ShelfFeedPreview.Snapshot(now, listOf(item))))
        val preview = file("shelf/${ShelfDiskBudget.fileName(item.image)}", now - 2 * day, ShelfLimits.imageBytes(true).toLong())
        repeat(60) { file("shelf/new-$it.jpg", now + it, 5L * 1024 * 1024) }
        CacheMaintenanceFiles.cleanShelf(shelf, now)
        val remaining = shelf.listFiles()!!.filter { it.extension == "jpg" }
        assertTrue(preview.exists())
        assertTrue(remaining.size <= ShelfDiskBudget.MAX_FILES)
        assertTrue(remaining.sumOf { it.length() } <= ShelfDiskBudget.MAX_BYTES)
    }

    @Test fun wallpaperCleanupProtectsSelectedAndAboutToBeShownButExpiresAbandonedSources() {
        val current = file("files/wall-scenery/current.jpg", now - 3 * day)
        file("files/wall-scenery/showing").writeText(current.name)
        val selectedNext = file("files/wall-scenery/next.jpg", now - 2 * day)
        file("files/wall-scenery/queue.json").writeText("""{"items":[{"name":"${selectedNext.name}"}]}""")
        val aboutToShow = file("files/wall-scenery/about-to-show.jpg", now - 2 * day)
        val expired = file("files/wall-scenery/expired.jpg", now - day)
        val fresh = file("files/wall-anime/fresh.jpg", now - day + 1)
        val abandoned = file("files/wall-art/abandoned.jpg", now - 3 * day)
        file("files/wall-art/showing").writeText(abandoned.name)
        val oldPart = file("files/wall-scenery/interrupted.jpg.part", now - day)
        val freshPart = file("files/wall-scenery/live.jpg.part", now - day + 1)
        val originals = listOf("wall-mine/photo.jpg", "wall-mine/upload.jpg.part", "wall-custom/custom.jpg",
            "wall-custom/download.jpg.part", "profile_avatar.png", "intro.mp4", "settings_icon.png", "intro.mp4.part",
            "accounts/database.db", "wall-anime/subfolder/original.jpg").map { file("files/$it", now - 5 * day) }

        CacheMaintenanceFiles.cleanWallpapers(File(temporary.root, "files"), now, "wall-scenery", setOf("wall-scenery/${aboutToShow.name}"))

        assertTrue(current.exists())
        assertTrue(selectedNext.exists())
        assertTrue(aboutToShow.exists())
        assertTrue(fresh.exists())
        assertTrue(freshPart.exists())
        assertFalse(expired.exists())
        assertFalse(abandoned.exists())
        assertFalse(oldPart.exists())
        assertTrue(originals.all { it.exists() })
    }

    @Test fun missingShowingMarkerStillKeepsAnOfflineSelectedWallpaper() {
        val oldest = file("files/wall-scenery/oldest.jpg", now - 3 * day)
        val newest = file("files/wall-scenery/newest.jpg", now - 2 * day)
        CacheMaintenanceFiles.cleanWallpapers(File(temporary.root, "files"), now, "wall-scenery")
        assertFalse(oldest.exists())
        assertTrue(newest.exists())
    }

    @Test fun bingKeepsRecentDatedHistoryBeyond24HoursButDeletesExpiredDatesAndDuplicateQualities() {
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        fun name(days: Long, quality: String = "hd-1920x1080") =
            today.minusDays(days).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) + "-$quality.jpg"
        val current = file("files/wall/${name(20)}", now - 20 * day)
        file("files/wall/showing").writeText(current.name)
        val recentHistory = file("files/wall/${name(2)}", now - 25 * day)
        val hd = file("files/wall/${name(3)}", now)
        val uhd = file("files/wall/${name(3, "uhd-3840x2160")}", now - day)
        val expired = file("files/wall/${name(11)}", now)

        CacheMaintenanceFiles.cleanWallpapers(File(temporary.root, "files"), now, "wall")

        assertTrue(current.exists())
        assertTrue(recentHistory.exists())
        assertFalse(hd.exists())
        assertTrue(uhd.exists())
        assertFalse(expired.exists())
    }

    @Test fun wallpaperBudgetsAreSharedAcrossDirectoriesAndPerSourceEvenForProtectedFiles() {
        val entries = CacheMaintenancePolicy.wallDirectories.flatMap { dir ->
            (1..20).map { CacheMaintenancePolicy.Wallpaper("$dir/$it.jpg", dir, 1, now + it) }
        }
        val protected = setOf(entries.first().key)
        val evictions = CacheMaintenancePolicy.wallpaperEvictions(entries, protected, now)
        val retained = entries.filter { it.key !in evictions }
        assertEquals(24, retained.size)
        assertTrue(retained.any { it.key in protected })
        assertTrue(retained.groupingBy { it.directory }.eachCount().values.all { it <= 16 })

        val huge = CacheMaintenancePolicy.Wallpaper("huge", "wall", Long.MAX_VALUE, now)
        val exactlyFull = CacheMaintenancePolicy.Wallpaper("full", "wall", CacheMaintenancePolicy.MAX_WALL_BYTES, now - 1)
        val small = CacheMaintenancePolicy.Wallpaper("small", "wall-anime", 1, now - 2)
        assertEquals(setOf("huge", "small"), CacheMaintenancePolicy.wallpaperEvictions(listOf(huge, exactlyFull, small), setOf("huge"), now))
    }

    @Test fun bingHasItsOwnTenFileCeilingAndProtectedCurrentTakesPriority() {
        val bing = (0..11).map { CacheMaintenancePolicy.Wallpaper("wall/$it.jpg", "wall", 1, now + it) }
        val evictions = CacheMaintenancePolicy.wallpaperEvictions(bing, setOf(bing.first().key), now)
        assertEquals(10, bing.count { it.key !in evictions })
        assertFalse(bing.first().key in evictions)
        val anime = (0..19).map { CacheMaintenancePolicy.Wallpaper("wall-anime/$it.jpg", "wall-anime", 1, now + it) }
        assertEquals(16, anime.size - CacheMaintenancePolicy.wallpaperEvictions(anime, emptySet(), now).size)
    }

    @Test fun twelveRecentBingFilesAreCappedEvenWhenAnotherSourceIsSelected() {
        val pictures = (0..11).map { file("files/wall/$it.jpg", now + it) }
        file("files/wall/showing").writeText(pictures.first().name)
        val filesDir = File(temporary.root, "files")
        CacheMaintenanceFiles.cleanWallpapers(filesDir, now, "wall-anime", setOf("wall/${pictures.first().name}"))
        assertEquals(10, pictures.count { it.exists() })
        assertTrue(pictures.first().exists())
        CacheMaintenanceFiles.cleanWallpapers(filesDir, now, "wall")
        assertEquals(10, pictures.count { it.exists() })
        assertTrue(pictures.first().exists())
    }

    @Test fun realWallpaperFilesRespectTheSharedByteLimitAndCurrentIsAccountedFor() {
        val current = file("files/wall-anime/current.jpg", now - 2 * day, 4L * 1024 * 1024)
        file("files/wall-anime/showing").writeText(current.name)
        repeat(40) { index ->
            val dir = CacheMaintenancePolicy.wallDirectories[index % CacheMaintenancePolicy.wallDirectories.size]
            file("files/$dir/$index.jpg", now + index, 4L * 1024 * 1024)
        }
        val filesDir = File(temporary.root, "files")
        CacheMaintenanceFiles.cleanWallpapers(filesDir, now, "wall-anime")
        val retained = CacheMaintenancePolicy.wallDirectories.flatMap { dir ->
            File(filesDir, dir).listFiles()?.filter { it.extension == "jpg" }.orEmpty()
        }
        assertTrue(current.exists())
        assertEquals(24, retained.size)
        assertEquals(CacheMaintenancePolicy.MAX_WALL_BYTES, retained.sumOf { it.length() })
    }
}
