package com.leslie.tvhome

import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Daily selection and publication, shared by the source reader and filesystem regression tests. */
internal object BingWallpaperPolicy {
    const val MAX_CACHE_DAYS = 10L
    fun signature(preference: String, uhd: Boolean) = "bing-history-v4:$preference:${if (uhd) "uhd" else "hd"}"
    private val fullDateFormat = DateTimeFormatter.ofPattern("uuuuMMddHHmm")

    fun sourceDay(now: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE)

    fun isToday(day: String?, now: Long, zone: ZoneId = ZoneId.systemDefault()) = day == sourceDay(now, zone)

    fun archiveUrls(day: String) = listOf("cn.bing.com", "www.bing.com").map {
        "https://$it/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=zh-CN&day=$day"
    }

    data class DailyImage(val date: String, val base: String, val caption: String, val publishedAt: Long)

    /** CN's midnight is 16:00 UTC on the previous startdate; use its actual publication instant. */
    fun archive(text: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<DailyImage> = runCatching {
        val images = JSONObject(text).getJSONArray("images")
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val seenDates = HashSet<String>()
        val seenImages = HashSet<String>()
        val selected = (0 until minOf(images.length(), 8)).mapNotNull { index -> runCatching image@ {
            val image = images.getJSONObject(index)
            val published = LocalDateTime.parse(image.getString("fullstartdate"), fullDateFormat).toInstant(ZoneOffset.UTC)
            val day = published.atZone(zone).toLocalDate()
            val date = image.getString("startdate")
            val base = image.getString("urlbase")
            if (day > today || day < today.minusDays(MAX_CACHE_DAYS - 1) ||
                date.length != 8 || cacheDate(date) == null || !base.startsWith("/th?id=")) return@image null
            val title = image.optString("title")
            val copy = image.optString("copyright")
            DailyImage(date, base, if (title.isNotEmpty() && !copy.startsWith(title)) "$title — $copy" else copy, published.toEpochMilli())
        }.getOrNull() }.sortedByDescending { it.publishedAt }.filter { image ->
            if (image.date in seenDates || image.base in seenImages) false
            else { seenDates += image.date; seenImages += image.base; true }
        }
        // If the market has not published today yet, keep yesterday on screen and retry later.
        if (selected.firstOrNull()?.let { isToday(sourceDay(it.publishedAt, zone), now, zone) } == true) selected else emptyList()
    }.getOrDefault(emptyList())

    fun ready(queue: JSONObject?, now: Long): Boolean =
        queue != null && isToday(queue.optString("sourceDay"), now) && queue.optBoolean("todayReady")

    /** Consume this before any history scan, including a forced advance immediately after refresh. */
    fun consumePriority(queue: JSONObject?, now: Long): Boolean {
        if (!ready(queue, now) || queue?.optBoolean("firstPending") != true) return false
        queue.put("firstPending", false)
        return true
    }

    data class Cached(val name: String, val modified: Long)

    fun cacheDate(name: String): LocalDate? = runCatching {
        LocalDate.parse(name.substringAfterLast('/').take(8), DateTimeFormatter.BASIC_ISO_DATE)
    }.getOrNull()

    fun sortTime(name: String, modified: Long): Long =
        cacheDate(name)?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli() ?: modified

    /** Real source dates outrank download times; retain one quality variant per date. */
    fun evictions(entries: List<Cached>, protected: Set<String>, now: Long, zone: ZoneId = ZoneId.systemDefault()): Set<String> {
        val oldest = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(MAX_CACHE_DAYS)
        val dates = HashSet<LocalDate>()
        var count = 0
        return entries.sortedWith(compareByDescending<Cached> { it.name in protected }
            .thenByDescending { sortTime(it.name, it.modified) }
            .thenByDescending { if (it.name.endsWith("-3840x2160.jpg")) 2 else if (it.name.contains("1920x1080")) 1 else 0 }
            .thenByDescending { it.modified }.thenBy { it.name }).mapNotNull { entry ->
            val date = cacheDate(entry.name)
            val old = date?.isBefore(oldest) ?: (now - entry.modified >= MAX_CACHE_DAYS * 86_400_000L)
            if ((entry.name !in protected && old) || count >= CacheMaintenancePolicy.MAX_BING_FILES || (date != null && date in dates)) entry.name
            else { count++; if (date != null) dates += date; null }
        }.toSet()
    }

    /** Failed requests/decodes never remove yesterday's last displayable picture. */
    fun publish(dir: File, replacement: File?, meta: JSONObject, now: Long, makeCurrent: Boolean = true,
                displayable: (File) -> Boolean): Boolean {
        if (replacement == null || !replacement.isFile || !displayable(replacement)) return false
        val previousShowing = runCatching { File(dir, "showing").readText() }.getOrNull()
        val samePicture = cacheDate(replacement.name)?.let { it == previousShowing?.let(::cacheDate) } == true
        val selectReplacement = makeCurrent || samePicture
        val showing = if (selectReplacement) replacement.name else previousShowing
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".jpg") }.orEmpty()
        val evicted = evictions(files.map { Cached(it.name, it.lastModified()) }, setOfNotNull(showing, replacement.name), now)
        val keep = files.filter { it.name !in evicted }.map { it.name }.toSet()
        val currentMeta = JSONObject()
        meta.keys().asSequence().filter { it in keep }.forEach { currentMeta.put(it, meta.get(it)) }
        File(dir, "meta.json").writeText(currentMeta.toString())
        if (selectReplacement) File(dir, "showing").writeText(replacement.name)
        files.filter { it.name in evicted }.forEach { it.delete() }
        return true
    }

    fun candidates(base: String, uhd: Boolean): List<String> = buildList {
        if (uhd) add("https://cn.bing.com${base}_UHD.jpg")
        add("https://cn.bing.com${base}_1920x1080.jpg")
    }

    fun canReuse(name: String, date: String, quality: String): Boolean =
        name.startsWith("$date-$quality-") &&
            (quality == "hd" || name.endsWith("-3840x2160.jpg"))

    fun cacheName(date: String, quality: String, width: Int, height: Int): String =
        "$date-$quality-${width}x${height}.jpg"

    /** A failed request or undecodable response advances to the next candidate. */
    fun <T : Any> firstUsable(urls: List<String>, load: (String) -> T?): T? {
        for (url in urls) runCatching { load(url) }.getOrNull()?.let { return it }
        return null
    }
}
