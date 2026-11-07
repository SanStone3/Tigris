package com.leslie.tvhome

import org.json.JSONObject
import java.net.URI

/** Anonymous public MV chart, not TV recommendations, account history or playback URLs. */
internal object ShelfNetease {
    const val TOP_MV_URL = "https://music.163.com/api/mv/toplist?limit=12&offset=0"

    /**
     * Use the returned cover unchanged apart from HTTPS. Even the API's pic16v9
     * field can be square: native Full HD / aspect-ratio checks remain mandatory.
     * No video URL, picture-ID derivation, resize parameters or account data is used.
     */
    fun topMv(text: String): List<Shelf.Item> = try {
        val root = JSONObject(text)
        if (root.optInt("code", -1) != 200) emptyList() else {
            val data = root.optJSONArray("data")
            val out = ArrayList<Shelf.Item>()
            val seen = HashSet<String>()
            if (data != null) for (index in 0 until data.length()) {
                if (out.size >= ShelfLimits.MAX_ITEMS) break
                val row = data.optJSONObject(index) ?: continue
                val id = row.opt("id")?.toString().orEmpty()
                if (!id.matches(Regex("[1-9][0-9]{0,18}"))) continue
                val title = string(row, "name").take(ShelfLimits.MAX_TITLE)
                val image = originalCover(string(row, "cover")) ?: continue
                if (title.isEmpty() || !seen.add(id)) continue
                out.add(Shelf.Item(title, string(row, "artistName").take(ShelfLimits.MAX_META), image,
                    requireFullHdArtwork = true))
            }
            out
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun string(row: JSONObject, key: String) = (row.opt(key) as? String).orEmpty().trim()

    private fun originalCover(raw: String): String? {
        val image = if (raw.startsWith("http://")) "https://" + raw.removePrefix("http://") else raw
        if (!image.startsWith("https://") || !ShelfLimits.isHttpUrl(image)) return null
        val uri = runCatching { URI(image) }.getOrNull() ?: return null
        return image.takeIf {
            uri.host?.matches(Regex("p[0-9]+\\.music\\.126\\.net")) == true &&
                uri.userInfo == null && uri.port == -1 && !uri.path.isNullOrBlank()
        }
    }
}
