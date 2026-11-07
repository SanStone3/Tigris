package com.leslie.tvhome

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** Called on an existing background worker; no timer or service is needed while Home is away. */
internal object CacheMaintenance {
    @Synchronized fun runIfDue(ctx: Context) {
        val now = System.currentTimeMillis()
        val prefs = ctx.getSharedPreferences("cache_maintenance", Context.MODE_PRIVATE)
        if (!CacheMaintenancePolicy.due(prefs.getLong("last_run", 0), now)) return
        runCatching {
            Shelf.maintainCache(ctx, now)
            Wallpapers.maintainCaches(ctx, now)
            prefs.edit().putLong("last_run", now).apply()
        }.onFailure { Log.w("CacheMaintenance", "daily cleanup failed", it) }
    }
}

internal object CacheMaintenancePolicy {
    const val UNUSED_MS = 24 * 60 * 60_000L
    const val PREVIEW_IMAGES_PER_SOURCE = 2
    const val MAX_WALL_FILES = 24
    const val MAX_WALL_BYTES = 96L * 1024 * 1024
    const val MAX_BING_FILES = 10
    const val MAX_WALL_FILES_PER_SOURCE = 16
    val wallDirectories = listOf("wall", "wall-scenery", "wall-fresh", "wall-anime", "wall-art", "wall-wallhaven")

    fun expired(usedAt: Long, now: Long) = usedAt <= now && now - usedAt >= UNUSED_MS

    fun due(lastRun: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        lastRun <= 0 || now < lastRun ||
            Instant.ofEpochMilli(lastRun).atZone(zone).toLocalDate() != Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

    data class Wallpaper(val key: String, val directory: String, val bytes: Long, val usedAt: Long)

    /** Offline/current pictures get first claim, but still share finite count and byte budgets. */
    fun wallpaperEvictions(entries: List<Wallpaper>, protected: Set<String>, now: Long): Set<String> {
        var count = 0
        var bytes = 0L
        val perSource = HashMap<String, Int>()
        val bingEvictions = BingWallpaperPolicy.evictions(entries.filter { it.directory == "wall" }
            .map { BingWallpaperPolicy.Cached(it.key, it.usedAt) }, protected, now)
        return bingEvictions + entries.filter { it.key !in bingEvictions }.sortedWith(compareByDescending<Wallpaper> { it.key in protected }
            .thenByDescending { if (it.directory == "wall") BingWallpaperPolicy.sortTime(it.key, it.usedAt) else it.usedAt }
            .thenBy { it.key }).mapNotNull { entry ->
            val size = entry.bytes.coerceAtLeast(0)
            val sourceCount = perSource[entry.directory] ?: 0
            val sourceLimit = if (entry.directory == "wall") MAX_BING_FILES else MAX_WALL_FILES_PER_SOURCE
            if ((entry.directory != "wall" && entry.key !in protected && expired(entry.usedAt, now)) || count >= MAX_WALL_FILES ||
                sourceCount >= sourceLimit || size > MAX_WALL_BYTES - bytes) entry.key
            else {
                count++
                bytes += size
                perSource[entry.directory] = sourceCount + 1
                null
            }
        }.toSet()
    }
}

/** Only named automatic-cache directories are traversed, and never recursively. */
internal object CacheMaintenanceFiles {
    fun shelfPreviewImages(dir: File, now: Long): Set<String> = buildSet {
        for (source in ShelfFeedPreview.SOURCES) {
            val snapshot = ShelfFeedPreview.read(dir, source, now) ?: continue
            val pictures = snapshot.items.flatMap { item ->
                ShelfLimits.artworkCandidates(item.image, item.fallbackImages).map(ShelfDiskBudget::fileName)
            }.distinct().map { File(dir, it) }
                .filter { it.isFile && it.length() in 1..ShelfLimits.imageBytes(true).toLong() }
                .sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name })
                .take(CacheMaintenancePolicy.PREVIEW_IMAGES_PER_SOURCE)
            addAll(pictures.map { it.name })
        }
    }

    /** Caller serializes with artwork publication and preview metadata replacement. */
    fun cleanShelf(dir: File, now: Long) {
        val protected = shelfPreviewImages(dir, now)
        val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
        val artwork = files.filter { it.name.endsWith(".jpg") }
        val evictions = ShelfDiskBudget.evictions(artwork.map {
            ShelfDiskBudget.Entry(it.name, it.length(), it.lastModified())
        }, protected, now).toSet()
        artwork.filter { it.name in evictions }.forEach { it.delete() }
        files.forEach { file ->
            if (file.name.endsWith(".part") && CacheMaintenancePolicy.expired(file.lastModified(), now)) file.delete()
            if (file.name.startsWith("feed-") && file.name.endsWith(".json")) {
                val source = ShelfFeedPreview.SOURCES.firstOrNull { file.name == "feed-$it-v${ShelfFeedPreview.VERSION}.json" }
                if (source == null || ShelfFeedPreview.read(dir, source, now) == null) file.delete()
            }
        }
    }

    /** The selected source keeps its showing picture (or newest fallback); old other sources expire. */
    fun cleanWallpapers(filesDir: File, now: Long, activeDirectory: String?, extraProtected: Set<String> = emptySet()) {
        val pictures = ArrayList<CacheMaintenancePolicy.Wallpaper>()
        val protected = extraProtected.toMutableSet()
        for (directory in CacheMaintenancePolicy.wallDirectories) {
            val dir = File(filesDir, directory)
            val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
            val jpgs = files.filter { it.name.endsWith(".jpg") }
            if (directory == activeDirectory) {
                val showing = runCatching { File(dir, "showing").readText() }.getOrNull()
                (jpgs.firstOrNull { it.name == showing } ?: jpgs.maxByOrNull { it.lastModified() })?.let {
                    protected += "$directory/${it.name}"
                }
                val queued = if (directory == "wall") null else runCatching {
                    JSONObject(File(dir, "queue.json").readText()).optJSONArray("items")?.optJSONObject(0)
                }.getOrNull()
                if (queued != null) jpgs.firstOrNull { picture ->
                    picture.name == queued.optString("name") || (directory == "wall" && queued.has("date") &&
                        BingWallpaperPolicy.canReuse(picture.name, queued.optString("date"), queued.optString("quality")))
                }?.let { protected += "$directory/${it.name}" }
            }
            pictures += jpgs.map {
                CacheMaintenancePolicy.Wallpaper("$directory/${it.name}", directory, it.length(), it.lastModified())
            }
            files.filter { it.name.endsWith(".part") && CacheMaintenancePolicy.expired(it.lastModified(), now) }
                .forEach { it.delete() }
        }
        CacheMaintenancePolicy.wallpaperEvictions(pictures, protected, now).forEach { File(filesDir, it).delete() }
    }
}
