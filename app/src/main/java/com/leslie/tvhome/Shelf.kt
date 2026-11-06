package com.leslie.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Top Shelf content. Android TV's own channel API is empty on the TVs this
 * was written for, so a few apps are filled from public web APIs: Bilibili
 * popular videos (云视听小电视 and the third-party client com.tutu.myblbl)
 * and Douyu live rooms. Mango TV, Youku, iQIYI and Tencent use anonymous public
 * website recommendations. These feeds do not expose personal viewing history.
 *
 * Settings can also point one dock app at a JSON URL. That feed is cached
 * with the same 15-minute policy. A bad address, unusable JSON, or a timeout
 * leaves the built-in feed for that package in place when one exists.
 */
object Shelf {
    data class Item(
        val title: String, val meta: String, val image: String,
        val fallbackImages: List<String> = emptyList(), val requireHdArtwork: Boolean = false,
        val requireFullHdArtwork: Boolean = false,
    )
    data class Source(val name: String, val fetch: () -> List<Item>)

    /** One user-entered feed, shown when [pkg] is focused in the dock. */
    data class Custom(val pkg: String, val url: String)

    internal enum class ShelfPick { CUSTOM, BUILTIN, NONE }

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
    /** Built-in responses are small; the cap only stops a runaway body. */
    private const val BUILTIN_JSON_BYTES = 2 * 1024 * 1024

    /** Package → which public feed it shares. Both Bilibili clients use `bilibili`. */
    internal val builtInPackages = mapOf(
        "com.xiaodianshi.tv.yst" to "bilibili",
        "com.tutu.myblbl" to "bilibili",
        "com.douyu.xl.douyutv" to "douyu",
        // Official TV APK manifests: pd.youku.com/CIBN and tv.qq.com.
        // The Mango package is also listed by Coocaa's public store API.
        "com.cibn.tv" to "youku",
        // Verified in the TV APK linked by iqiyi.com/appstore.html.
        "com.gitvdemo.video" to "iqiyi",
        "com.ktcp.video" to "tencent",
        "com.hunantv.market" to "mango",
        "com.hunantv.license" to "mango",
        "com.starcor.mango" to "mango",
    )

    private data class Cached(val name: String, val feed: ShelfRefreshPolicy.Feed<Item>)

    private val sources get() = builtInPackages.mapValues { (_, kind) ->
        when (kind) {
            "bilibili" -> Source(I18n.text(R.string.i18n_shelf_001), ::bilibili)
            "douyu" -> Source(I18n.text(R.string.i18n_shelf_002), ::douyu)
            "mango" -> Source(I18n.text(R.string.shelf_mango_source)) {
                ShelfWebFeeds.mango(get(ShelfWebFeeds.MANGO_URL, BUILTIN_JSON_BYTES).decodeToString())
            }
            "youku" -> Source(I18n.text(R.string.shelf_youku_source)) {
                ShelfWebFeeds.youku(get(ShelfWebFeeds.YOUKU_URL, BUILTIN_JSON_BYTES).decodeToString())
            }
            "iqiyi" -> Source(I18n.text(R.string.shelf_iqiyi_source)) {
                ShelfWebFeeds.iqiyi(get(ShelfWebFeeds.IQIYI_URL, BUILTIN_JSON_BYTES).decodeToString())
            }
            "tencent" -> Source(I18n.text(R.string.shelf_tencent_source)) {
                ShelfWebFeeds.tencent(get(ShelfWebFeeds.TENCENT_URL, BUILTIN_JSON_BYTES, ShelfWebFeeds.tencentRequest()).decodeToString())
            }
            else -> error("Unknown shelf source: $kind")
        }
    }
    private val cache = HashMap<String, Cached>()
    private val shownName = HashMap<String, String>()
    private val artworkLocks = Array(32) { Any() }
    private val artworkBudgetLock = Any()
    private val rejectedArtwork = ShelfArtworkRejections()

    private fun builtInKey(pkg: String) = builtInPackages[pkg]?.let { "builtin\u0000$it" }
    private fun customKey(pkg: String, custom: Custom) = "custom\u0000$pkg\u0000${custom.url.trim()}"
    private fun previewSource(pkg: String, custom: Custom?) = ShelfFeedPreview.eligibleSource(
        builtInPackages[pkg], pickShelf(pkg, custom, customOk = true) == ShelfPick.CUSTOM)

    /**
     * Custom wins only when the linked package matches and the body was usable.
     * Otherwise a known package keeps its built-in feed.
     */
    internal fun pickShelf(pkg: String, custom: Custom?, customOk: Boolean): ShelfPick {
        val linked = custom?.pkg?.trim().orEmpty()
        val url = custom?.url?.trim().orEmpty()
        if (linked == pkg && ShelfLimits.isHttpUrl(url) && customOk) return ShelfPick.CUSTOM
        if (pkg in builtInPackages) return ShelfPick.BUILTIN
        return ShelfPick.NONE
    }

    fun has(pkg: String, custom: Custom? = null) = pickShelf(pkg, custom, customOk = true) != ShelfPick.NONE

    fun sourceName(pkg: String) = synchronized(cache) {
        shownName[pkg] ?: sources[pkg]?.name ?: ""
    }

    /** Items for [pkg], cached for a while; empty if the app has no shelf or the network failed. */
    fun items(pkg: String, custom: Custom? = null, isCurrent: () -> Boolean = { true }): List<Item> {
        if (!isCurrent()) return emptyList()
        if (pickShelf(pkg, custom, customOk = true) == ShelfPick.CUSTOM) {
            val url = custom!!.url.trim()
            val key = customKey(pkg, custom)
            var fetched = readCache(key, pkg)
            if (fetched == null) {
                fetched = try {
                    fetchCustom(url, isCurrent).orEmpty()
                } catch (e: Exception) {
                    if (isCurrent()) Log.w("Shelf", "custom $pkg failed", e)
                    null
                }
                if (!isCurrent()) return emptyList()
                fetched = writeCache(key, pkg, I18n.text(R.string.shelf_custom_source), fetched)
            }
            if (!fetched.isNullOrEmpty()) {
                return fetched
            }
        }
        if (!isCurrent()) return emptyList()
        val src = sources[pkg] ?: return emptyList()
        val key = builtInKey(pkg) ?: return emptyList()
        readCache(key, pkg)?.let { return it }
        val started = System.nanoTime()
        Log.i("Shelf", "fetch_start pkg=$pkg ms=0")
        val list = try {
            src.fetch()
        } catch (e: Exception) {
            if (isCurrent()) Log.w("Shelf", "$pkg failed", e)
            null
        }
        if (!isCurrent()) return emptyList()
        Log.i("Shelf", "fetch pkg=$pkg count=${list?.size ?: 0} ms=${(System.nanoTime() - started) / 1_000_000}")
        return writeCache(key, pkg, src.name, list)
    }

    /** Background-only local metadata read. It supplies fallback items, never a fresh online TTL. */
    fun previewItems(ctx: Context, pkg: String, custom: Custom?, isCurrent: () -> Boolean): List<Item> {
        val source = previewSource(pkg, custom) ?: return emptyList()
        val key = builtInKey(pkg) ?: return emptyList()
        synchronized(cache) { cache[key]?.feed?.items?.takeIf { it.isNotEmpty() }?.let { return it } }
        val now = System.currentTimeMillis()
        val snapshot = ShelfFeedPreview.read(File(ctx.cacheDir, "shelf"), source, now, isCurrent) ?: return emptyList()
        if (!isCurrent()) return emptyList()
        synchronized(cache) {
            if (cache[key] == null) cache[key] = Cached(sources.getValue(pkg).name, ShelfFeedPreview.staleFeed(snapshot, now))
        }
        return snapshot.items
    }

    /** Persist only a successfully fetched public feed whose artwork has been displayed/validated. */
    fun rememberPreview(ctx: Context, pkg: String, custom: Custom?, items: List<Item>, isCurrent: () -> Boolean) {
        val source = previewSource(pkg, custom) ?: return
        val key = builtInKey(pkg) ?: return
        val feed = synchronized(cache) { cache[key]?.feed?.takeIf { !it.failed && it.items == items } } ?: return
        ShelfFeedPreview.write(File(ctx.cacheDir, "shelf"), source, ShelfFeedPreview.Snapshot(feed.at, items), isCurrent)
    }

    /** How long the current dock source can remain on screen before its cached feed expires. */
    fun refreshDelayMs(pkg: String, custom: Custom? = null): Long = synchronized(cache) {
        val now = System.currentTimeMillis()
        val deadlines = ArrayList<Long>(2)
        if (pickShelf(pkg, custom, customOk = true) == ShelfPick.CUSTOM) {
            val hit = cache[customKey(pkg, custom!!)]
            val delay = ShelfRefreshPolicy.feedDelayMs(hit?.feed, now)
            if (!hit?.feed?.items.isNullOrEmpty()) return@synchronized delay
            deadlines.add(delay)
        }
        builtInKey(pkg)?.let { deadlines.add(ShelfRefreshPolicy.feedDelayMs(cache[it]?.feed, now)) }
        deadlines.minOrNull() ?: ShelfRefreshPolicy.TTL_MS
    }

    /** Downloads (or reads from the disk cache) an item's picture. */
    fun image(ctx: Context, item: Item, isCurrent: () -> Boolean = { true }): Bitmap? {
        for (url in ShelfLimits.artworkCandidates(item.image, item.fallbackImages)) {
            if (!isCurrent()) return null
            image(ctx, url, item.requireHdArtwork, item.requireFullHdArtwork, isCurrent)?.let { return it }
        }
        return null
    }

    fun image(ctx: Context, url: String): Bitmap? = image(ctx, url, false, false) { true }

    /** Local preview must neither start a download nor wait for a download's per-URL lock. */
    fun cachedImage(ctx: Context, item: Item, isCurrent: () -> Boolean): Bitmap? {
        if (!item.requireFullHdArtwork) return null
        for (url in ShelfLimits.artworkCandidates(item.image, item.fallbackImages)) {
            if (!isCurrent()) return null
            val file = File(File(ctx.cacheDir, "shelf"), Integer.toHexString(url.hashCode()) + ".jpg")
            if (!file.isFile || file.length() > ShelfLimits.imageBytes(true)) continue
            decodeArtwork(file, url, checkedArtwork = true, requireFullHd = true, isCurrent = isCurrent)?.let {
                file.setLastModified(System.currentTimeMillis())
                return it
            }
        }
        return null
    }

    private fun image(ctx: Context, url: String, requireHd: Boolean, requireFullHd: Boolean, isCurrent: () -> Boolean): Bitmap? =
        synchronized(artworkLocks[(url.hashCode() and Int.MAX_VALUE) % artworkLocks.size]) artwork@ {
        if (!isCurrent() || !ShelfLimits.isHttpUrl(url)) return@artwork null
        val checkedArtwork = requireHd || requireFullHd || IqiyiArtworkPolicy.isLandscapeCandidate(url)
        val rejectionKey = "$requireFullHd:$checkedArtwork:$url"
        if (checkedArtwork && rejectedArtwork.contains(rejectionKey, System.currentTimeMillis())) return@artwork null
        val dir = File(ctx.cacheDir, "shelf").apply { mkdirs() }
        val f = File(dir, Integer.toHexString(url.hashCode()) + ".jpg")
        val tmp = File(dir, f.name + ".part")
        val maxBytes = ShelfLimits.imageBytes(requireFullHd)
        // A shared URL previously cached by a Full HD feed does not raise a custom feed's budget.
        if (f.exists() && f.length() > maxBytes) return@artwork null
        try {
            val download = !f.exists()
            if (download) {
                tmp.writeBytes(get(url, maxBytes, isCurrent = isCurrent))
                if (!isCurrent()) { tmp.delete(); return@artwork null }
            }
            // Only publishing and accounting share a global lock; unrelated downloads/decode can overlap.
            synchronized(artworkBudgetLock) {
                if (download && !tmp.renameTo(f)) throw java.io.IOException("could not cache shelf artwork")
                f.setLastModified(System.currentTimeMillis())
                val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".jpg") }.orEmpty()
                val evictions = ShelfDiskBudget.evictions(files.map { ShelfDiskBudget.Entry(it.name, it.length(), it.lastModified()) }).toSet()
                files.filter { it.name in evictions }.forEach { it.delete() }
            }
        } catch (e: Exception) {
            tmp.delete()
            if (isCurrent()) Log.w("Shelf", "image $url failed", e)
            return@artwork null
        }
        decodeArtwork(f, url, checkedArtwork, requireFullHd, isCurrent)
    }

    /** Disk previews and downloads share the exact native bounds, decoded size and crop checks. */
    private fun decodeArtwork(f: File, url: String, checkedArtwork: Boolean, requireFullHd: Boolean,
                              isCurrent: () -> Boolean): Bitmap? {
        if (!isCurrent()) return null
        val rejectionKey = "$requireFullHd:$checkedArtwork:$url"
        fun accepted(width: Int, height: Int) = when {
            requireFullHd -> ShelfLimits.isFullHdLandscape(width, height)
            checkedArtwork -> ShelfLimits.isHdLandscape(width, height)
            else -> true
        }
        fun reject() {
            if (checkedArtwork) rejectedArtwork.add(rejectionKey, System.currentTimeMillis())
            f.delete()
        }
        var bitmap: Bitmap? = null
        return runCatching {
            // Validate the downloaded file's native bounds before sampling or scaling it.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) { f.delete(); return@runCatching null }
            if (!accepted(bounds.outWidth, bounds.outHeight)) { reject(); return@runCatching null }
            val decoded = decodeCapped(f.path, isCurrent)
            bitmap = decoded
            if (decoded == null || !isCurrent() || !accepted(decoded.width, decoded.height)) {
                if (decoded != null && isCurrent()) reject()
                decoded?.recycle()
                bitmap = null
                return@runCatching null
            }
            val trimmed = trimBars(decoded)
            if (trimmed !== decoded) decoded.recycle()
            bitmap = trimmed
            if (!isCurrent() || !accepted(trimmed.width, trimmed.height)) {
                if (isCurrent()) reject()
                trimmed.recycle(); bitmap = null
                return@runCatching null
            }
            darken(trimmed).also {
                bitmap = it
                // Start the GPU upload now, off the frame that first shows it.
                it.prepareToDraw()
            }
        }.onFailure {
            bitmap?.takeUnless { it.isRecycled }?.recycle()
            f.delete()
            Log.w("Shelf", "decode $url failed", it)
        }.getOrNull()
    }

    private fun fetchCustom(url: String, isCurrent: () -> Boolean): List<Item>? {
        val text = get(url, ShelfLimits.MAX_JSON_BYTES, isCurrent = isCurrent).toString(Charsets.UTF_8)
        val parsed = ShelfJson.parse(text) ?: return null
        if (parsed.isEmpty()) return null
        return parsed.map { Item(it.title, it.meta, it.image) }
    }

    private fun readCache(key: String, pkg: String): List<Item>? = synchronized(cache) {
        val hit = cache[key] ?: return null
        if (ShelfRefreshPolicy.feedDelayMs(hit.feed, System.currentTimeMillis()) == 0L) return null
        if (hit.feed.items.isNotEmpty()) shownName[pkg] = hit.name
        hit.feed.items
    }

    private fun writeCache(key: String, pkg: String, name: String, items: List<Item>?): List<Item> = synchronized(cache) {
        val previous = cache[key]
        val feed = ShelfRefreshPolicy.afterFetch(previous?.feed, items, System.currentTimeMillis())
        val source = if (items == null) previous?.name ?: name else name
        cache[key] = Cached(source, feed)
        shownName[pkg] = source
        if (items != null) Log.i("Shelf", "feed pkg=$pkg count=${items.size}")
        feed.items
    }

    private fun decodeCapped(path: String, isCurrent: () -> Boolean): Bitmap? {
        if (!isCurrent()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = ShelfLimits.sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        val decoded = BitmapFactory.decodeFile(path, opts) ?: return null
        if (!isCurrent()) { decoded.recycle(); return null }
        val (width, height) = ShelfLimits.fittedSize(decoded.width, decoded.height)
        if (width == decoded.width && height == decoded.height) return decoded
        return try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { out ->
                android.graphics.Canvas(out).drawBitmap(decoded, null, android.graphics.Rect(0, 0, width, height),
                    android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            }
        }
        finally { decoded.recycle() }
    }

    /**
     * Bakes the scrim that keeps the title readable into the picture itself:
     * a full-screen gradient layer on top would cost the TV's GPU three more
     * full-screen passes on every frame of a cross-fade.
     */
    private fun darken(b: Bitmap): Bitmap {
        val out = if (b.isMutable) b else b.copy(Bitmap.Config.ARGB_8888, true).also { b.recycle() }
        ShelfScrim().apply { setBounds(0, 0, out.width, out.height) }.draw(android.graphics.Canvas(out))
        return out
    }

    /** Live-stream covers are often letterboxed; cut the black bars so the picture fills the screen. */
    private fun trimBars(b: Bitmap): Bitmap {
        fun dark(y: Int): Boolean {
            var max = 0
            for (k in 0 until 32) {
                val c = b.getPixel(k * (b.width - 1) / 31, y)
                max = maxOf(max, (c shr 16) and 255, (c shr 8) and 255, c and 255)
            }
            return max < 28
        }
        var top = 0
        while (top < b.height / 4 && dark(top)) top++
        var bottom = b.height
        while (bottom > b.height * 3 / 4 && dark(bottom - 1)) bottom--
        if (top < 4 && b.height - bottom < 4) return b
        return Bitmap.createBitmap(b.width, bottom - top, Bitmap.Config.ARGB_8888).also { out ->
            android.graphics.Canvas(out).drawBitmap(b, android.graphics.Rect(0, top, b.width, bottom),
                android.graphics.Rect(0, 0, out.width, out.height), null)
        }
    }

    private fun bilibili(): List<Item> {
        val list = JSONObject(get("https://api.bilibili.com/x/web-interface/popular?ps=12&pn=1", BUILTIN_JSON_BYTES).decodeToString())
            .getJSONObject("data").getJSONArray("list")
        return (0 until minOf(list.length(), ShelfLimits.MAX_ITEMS)).map { list.getJSONObject(it) }.map { v ->
            Item(
                v.optString("title"),
                I18n.text(R.string.i18n_shelf_003, v.getJSONObject("owner").optString("name"), count(v.getJSONObject("stat").optLong("view"))),
                v.optString("pic").replace("http://", "https://"),
                requireFullHdArtwork = true,
            )
        }
    }

    private fun douyu(): List<Item> {
        val list = JSONObject(get("https://www.douyu.com/japi/weblist/apinc/allpage/6/1", BUILTIN_JSON_BYTES).decodeToString())
            .getJSONObject("data").getJSONArray("rl")
        return (0 until list.length()).map { list.getJSONObject(it) }
            .filter { it.optString("rs16").isNotEmpty() }
            .take(12)
            .map { r ->
                Item(
                    r.optString("rn"),
                    I18n.text(R.string.i18n_shelf_004, r.optString("nn"), r.optString("c2name"), count(r.optLong("ol"))),
                    // "/dy4" is the 1280×720 JPEG rendition of the AVIF cover.
                    r.optString("rs16").substringBeforeLast('/') + "/dy4",
                )
            }
    }

    private fun count(n: Long): String = if (I18n.isChinese()) {
        when {
            n >= 100_000_000 -> I18n.text(R.string.i18n_shelf_005, n / 100_000_000.0)
            n >= 10_000 -> I18n.text(R.string.i18n_shelf_006, n / 10_000.0)
            else -> n.toString()
        }
    } else {
        when {
            n >= 1_000_000 -> I18n.text(R.string.i18n_shelf_005, n / 1_000_000.0)
            n >= 1_000 -> I18n.text(R.string.i18n_shelf_006, n / 1_000.0)
            else -> n.toString()
        }
    }

    private fun get(url: String, maxBytes: Int, body: String? = null, isCurrent: () -> Boolean = { true }): ByteArray {
        if (!isCurrent()) throw java.io.IOException("shelf request cancelled")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 8000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        // B站's API answers with an empty body when sent a Referer from outside its pages.
        if ("douyu" in url) c.setRequestProperty("Referer", "https://www.douyu.com/")
        if (url == ShelfWebFeeds.TENCENT_URL) c.setRequestProperty("Referer", "https://v.qq.com/")
        try {
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            val advertised = c.contentLengthLong
            if (advertised > maxBytes) throw java.io.IOException("response too large")
            return c.inputStream.use { ShelfLimits.readAtMost(it, maxBytes, isCurrent) }
        } finally {
            c.disconnect()
        }
    }
}
