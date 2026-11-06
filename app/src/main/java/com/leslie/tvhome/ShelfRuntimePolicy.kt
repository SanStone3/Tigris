package com.leslie.tvhome

/** Shared by feed caching, foreground refresh and rejected-artwork caching. */
internal object ShelfRefreshPolicy {
    const val TTL_MS = 15 * 60_000L

    fun delayMs(cachedAt: Long?, now: Long): Long =
        if (cachedAt == null) 0L else (TTL_MS - (now - cachedAt).coerceAtLeast(0)).coerceIn(0L, TTL_MS)

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

    fun evictions(entries: List<Entry>): List<String> {
        var count = 0
        var bytes = 0L
        return entries.sortedWith(compareByDescending<Entry> { it.modified }.thenBy { it.name }).mapNotNull {
            val size = it.bytes.coerceAtLeast(0)
            if (count >= MAX_FILES || size > MAX_BYTES - bytes) it.name
            else { count++; bytes += size; null }
        }
    }
}
