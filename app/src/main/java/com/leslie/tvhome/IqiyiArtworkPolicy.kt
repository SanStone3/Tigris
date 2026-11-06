package com.leslie.tvhome

import java.net.URI

/** Official cover sizes: http://dev.tv.iqiyi.com/cooperation/figureSize/ .
 * Only the sized `601` programme covers observed in the public ranking are rewritten.
 * A candidate URL is not proof of its pixels: Shelf.image checks the downloaded bitmap.
 */
internal object IqiyiArtworkPolicy {
    private val host = Regex("pic[0-9]\\.iqiyipic\\.com")
    private val sizedCover = Regex("^(/image/(?:[A-Za-z0-9_-]+/)*[av]_[0-9]+_m_601(?:_m[0-9]+)?)_([1-9][0-9]{0,4})_([1-9][0-9]{0,4})\\.(jpg|jpeg)$")
    private data class Cover(val host: String, val base: String, val width: Int, val height: Int, val extension: String)

    fun landscapeCandidate(raw: String): String? = parse(raw)?.let {
        "https://${it.host}${it.base}_1920_1080.${it.extension}"
    }

    fun isLandscapeCandidate(raw: String): Boolean = parse(raw)?.let { it.width == 1920 && it.height == 1080 } == true

    fun acceptsDimensions(width: Int, height: Int) = width >= 1280 && height >= 720 && width > height

    private fun parse(raw: String): Cover? {
        val text = raw.trim().let { if (it.startsWith("//")) "https:$it" else it }
        if (!ShelfLimits.isHttpUrl(text)) return null
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        val name = uri.host?.lowercase() ?: return null
        if (!host.matches(name) || uri.rawUserInfo != null || uri.port != -1 || uri.rawQuery != null || uri.rawFragment != null) return null
        // This literal path grammar also excludes encoded traversal, dot segments and empty segments.
        val match = sizedCover.matchEntire(uri.rawPath.orEmpty()) ?: return null
        return Cover(name, match.groupValues[1], match.groupValues[2].toInt(), match.groupValues[3].toInt(), match.groupValues[4])
    }
}
