package com.leslie.tvhome

/** Pure policy shared by the archive reader and mocked download tests. */
internal object BingWallpaperPolicy {
    fun signature(preference: String, uhd: Boolean) = "bing-v2:$preference:${if (uhd) "uhd" else "hd"}"

    fun include(date: String, base: String, dates: MutableSet<String>, images: MutableSet<String>): Boolean {
        if (!date.matches(Regex("[0-9]{8}")) || !base.startsWith("/th?id=") ||
            date in dates || base in images) return false
        dates.add(date)
        images.add(base)
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
