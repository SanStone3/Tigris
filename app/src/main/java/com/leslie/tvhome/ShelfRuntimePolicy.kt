package com.leslie.tvhome

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Only public priority feeds may persist metadata; a preview never makes an online cache fresh. */
internal object ShelfFeedPreview {
    const val VERSION = 1
    const val MAX_BYTES = 128 * 1024
    const val MAX_AGE_MS = 7 * 24 * 60 * 60_000L
    val SOURCES = setOf("bilibili", "youku", "netease")
    data class Snapshot(val at: Long, val items: List<Shelf.Item>)

    fun eligibleSource(kind: String?, custom: Boolean): String? =
        kind?.takeIf { !custom && it in SOURCES }

    private fun file(dir: File, source: String) = File(dir, "feed-$source-v$VERSION.json")

    fun read(dir: File, source: String, now: Long, isCurrent: () -> Boolean = { true }): Snapshot? {
        if (eligibleSource(source, false) == null || !isCurrent()) return null
        return runCatching {
            val file = file(dir, source)
            if (!file.isFile || file.length() !in 1..MAX_BYTES.toLong()) return null
            val bytes = file.inputStream().use { ShelfLimits.readAtMost(it, MAX_BYTES, isCurrent) }
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            if (json.getInt("version") != VERSION || json.getString("source") != source) return null
            val at = json.getLong("at")
            if (at <= 0 || at > now || now - at > MAX_AGE_MS) return null
            val array = json.getJSONArray("items")
            if (array.length() !in 1..ShelfLimits.MAX_ITEMS) return null
            val items = (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                val title = item.getString("title")
                val meta = item.getString("meta")
                val image = item.getString("image")
                val fallbacks = item.getJSONArray("fallbacks")
                if (title.isBlank() || title.length > ShelfLimits.MAX_TITLE || meta.length > ShelfLimits.MAX_META ||
                    !ShelfLimits.isHttpUrl(image) || fallbacks.length() > 2) return null
                val urls = (0 until fallbacks.length()).map { fallbacks.getString(it) }
                if (urls.any { !ShelfLimits.isHttpUrl(it) }) return null
                Shelf.Item(title, meta, image, urls, requireFullHdArtwork = true)
            }
            if (isCurrent()) Snapshot(at, items) else null
        }.getOrNull()
    }

    /** Same-directory atomic replacement keeps the previous complete snapshot on interruption. */
    @Synchronized fun write(dir: File, source: String, snapshot: Snapshot, isCurrent: () -> Boolean = { true }): Boolean {
        if (eligibleSource(source, false) == null || snapshot.at <= 0 || !isCurrent()) return false
        var temporary: File? = null
        return try {
            val items = snapshot.items.take(ShelfLimits.MAX_ITEMS)
            if (items.isEmpty() || items.any { it.title.isBlank() || !it.requireFullHdArtwork || !ShelfLimits.isHttpUrl(it.image) }) return false
            val array = JSONArray()
            items.forEach { item ->
                array.put(JSONObject().put("title", item.title.take(ShelfLimits.MAX_TITLE))
                    .put("meta", item.meta.take(ShelfLimits.MAX_META)).put("image", item.image)
                    .put("fallbacks", JSONArray(ShelfLimits.artworkCandidates(item.image, item.fallbackImages).drop(1))))
            }
            val bytes = JSONObject().put("version", VERSION).put("source", source).put("at", snapshot.at)
                .put("items", array).toString().toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_BYTES || !isCurrent()) return false
            if (!dir.isDirectory && !dir.mkdirs()) return false
            temporary = File.createTempFile("feed-$source-", ".part", dir)
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            if (!isCurrent()) return false
            Files.move(temporary.toPath(), file(dir, source).toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (_: Exception) {
            false
        } finally {
            temporary?.delete()
        }
    }

    fun staleFeed(snapshot: Snapshot, now: Long) =
        ShelfRefreshPolicy.Feed(minOf(snapshot.at, now - ShelfRefreshPolicy.TTL_MS), snapshot.items, failed = false)
}

/** Shared by feed caching, foreground refresh and rejected-artwork caching. */
internal object ShelfRefreshPolicy {
    const val TTL_MS = 15 * 60_000L
    const val RETRY_MS = 10_000L

    fun delayMs(cachedAt: Long?, now: Long, ttlMs: Long = TTL_MS): Long =
        if (cachedAt == null) 0L else (ttlMs - (now - cachedAt).coerceAtLeast(0)).coerceIn(0L, ttlMs)

    data class Feed<T>(val at: Long, val items: List<T>, val failed: Boolean)

    /** Null is an exception, not a successful empty response; keep the last usable feed. */
    fun <T> afterFetch(previous: Feed<T>?, fetched: List<T>?, now: Long) =
        Feed(now, fetched ?: previous?.items.orEmpty(), failed = fetched == null)

    fun <T> feedDelayMs(feed: Feed<T>?, now: Long) =
        delayMs(feed?.at, now, if (feed?.failed == true) RETRY_MS else TTL_MS)

    /** Even an expired feed waits after a failed scan, avoiding immediate failed reloads. */
    fun nextDelayMs(feedDelayMs: Long, artworkFailedAt: Long?, now: Long): Long =
        if (artworkFailedAt == null) feedDelayMs else delayMs(artworkFailedAt, now, RETRY_MS)

    fun <T> retainedIndex(items: List<T>, current: T?, sameArtwork: (T, T) -> Boolean): Int =
        if (current == null) -1 else items.indexOfFirst { sameArtwork(it, current) }
}

/** Window and deferred dock-focus restoration pause work without discarding the displayed picture. */
internal object ShelfFocusPolicy {
    enum class Action { PAUSE, HIDE, ACTIVE }
    fun action(resumed: Boolean, windowFocused: Boolean, eligible: Boolean, restoringDockFocus: Boolean = false) = when {
        !resumed || !windowFocused || restoringDockFocus -> Action.PAUSE
        !eligible -> Action.HIDE
        else -> Action.ACTIVE
    }
}

/** Main-thread request ownership; an old completion cannot unlock a newer feed request. */
internal class ShelfFeedRequests {
    private var loading: ShelfSession.Ticket? = null
    fun isLoading(ticket: ShelfSession.Ticket) = loading == ticket
    fun begin(ticket: ShelfSession.Ticket) { loading = ticket }
    fun finish(ticket: ShelfSession.Ticket): Boolean {
        if (loading != ticket) return false
        loading = null
        return true
    }
    fun cancel() { loading = null }
}

/** One bounded pass: failures advance, wrapping visits an item at most once. */
internal object ShelfCandidateScan {
    const val MAX_CANDIDATES = 30
    data class Match<T>(val index: Int, val value: T)

    fun <T, R : Any> first(
        items: List<T>, start: Int = 0, limit: Int = MAX_CANDIDATES,
        isCurrent: () -> Boolean = { true }, discard: (R) -> Unit = {}, load: (T) -> R?,
    ): Match<R>? {
        if (items.isEmpty()) return null
        val first = ((start % items.size) + items.size) % items.size
        repeat(minOf(items.size, limit.coerceAtLeast(0), MAX_CANDIDATES)) { offset ->
            if (!isCurrent()) return null
            val index = (first + offset) % items.size
            val value = load(items[index])
            if (!isCurrent()) { if (value != null) discard(value); return null }
            if (value != null) return Match(index, value)
        }
        return null
    }
}

/** Only proven dimension failures are remembered; network failures retain their normal behavior. */
internal class ShelfArtworkRejections(private val capacity: Int = 256) {
    private val rejected = LinkedHashMap<String, Long>()

    @Synchronized fun contains(key: String, now: Long): Boolean {
        rejected.entries.removeAll { ShelfRefreshPolicy.delayMs(it.value, now) == 0L }
        return key in rejected
    }

    @Synchronized fun add(key: String, now: Long) {
        rejected.remove(key)
        rejected[key] = now
        while (rejected.size > capacity.coerceAtLeast(0)) rejected.remove(rejected.keys.first())
    }
}

internal object ShelfDiskBudget {
    const val MAX_FILES = 60
    const val MAX_BYTES = 120L * 1024 * 1024
    data class Entry(val name: String, val bytes: Long, val modified: Long)

    fun fileName(url: String) = Integer.toHexString(url.hashCode()) + ".jpg"

    fun evictions(entries: List<Entry>, protected: Set<String> = emptySet(), now: Long? = null): List<String> {
        var count = 0
        var bytes = 0L
        return entries.sortedWith(compareByDescending<Entry> { it.name in protected }
            .thenByDescending { it.modified }.thenBy { it.name }).mapNotNull {
            val size = it.bytes.coerceAtLeast(0)
            val expired = now != null && it.name !in protected && CacheMaintenancePolicy.expired(it.modified, now)
            if (expired || count >= MAX_FILES || size > MAX_BYTES - bytes) it.name
            else { count++; bytes += size; null }
        }
    }
}
