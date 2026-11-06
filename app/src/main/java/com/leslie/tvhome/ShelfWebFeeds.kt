package com.leslie.tvhome

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Public website recommendations, never account history or TV playback links.
 * Shapes checked against the official sites on 2026-11-06. Website APIs can change;
 * an empty/changed document simply yields no shelf. No JavaScript is executed.
 */
internal object ShelfWebFeeds {
    const val YOUKU_URL = "https://www.youku.com/ku/webhome"
    const val IQIYI_URL = "https://www.iqiyi.com/ranks1PCW/home"
    // Anonymous public homepage banners: no account, device identifier or client-version impersonation.
    const val MANGO_URL = "https://mob-st.bz.mgtv.com/odin/c1/channel/index?vclassId=60&type=10&pageNo=1&pageSize=10"
    // v.qq.com's anonymous channel prefetch. Empty identity/cookie fields are intentional.
    const val TENCENT_URL = "https://pbaccess.video.qq.com/trpc.vector_layout.page_view.PageService/getPage?video_appid=3000010&vversion_platform=2"

    fun tencentRequest(): String = """
        {"page_params":{"page_type":"channel","page_id":"100101","scene":"channel","new_mark_label_enabled":"1","vl_to_mvl":"","ad_exp_ids":"","ams_cookies":"","ad_trans_data":"{\"ad_request_id\":\"\",\"game_sessions\":[]}","skip_privacy_types":"0","support_click_scan":"1"},"page_bypass_params":{"params":{"platform_id":"2","caller_id":"3000010","data_mode":"default","user_mode":"default","specified_strategy":"","page_type":"channel","page_id":"100101","scene":"channel","new_mark_label_enabled":"1"},"scene":"channel","app_version":"","abtest_bypass_id":""},"page_context":null}
    """.trimIndent()

    fun youku(html: String): List<Shelf.Item> = safely {
        val assignment = Regex("window\\.__INITIAL_DATA__\\s*=\\s*").find(html) ?: return@safely emptyList()
        val end = html.indexOf("</script>", assignment.range.last + 1)
        if (end < 0) return@safely emptyList()
        val text = html.substring(assignment.range.last + 1, end).trim().removeSuffix(";")
        // The server's data includes bare `undefined`. Normalize that token, preserving
        // quoted text verbatim; this is data parsing, not evaluation of the script.
        val json = Regex("\"(?:[^\"\\\\]|\\\\.)*\"|\\bundefined\\b").replace(text) {
            if (it.value == "undefined") "null" else it.value
        }
        val out = ArrayList<Shelf.Item>()
        JSONObject(json).optJSONArray("moduleList").objects().forEach { module ->
            // Only the editorial hero carousel; other modules include ads and arbitrary links.
            if (module.optString("typeName") != "KU_FLIX_LUNBO_V2") return@forEach
            module.optJSONArray("components").objects().forEach { component ->
                component.optJSONArray("itemList").objects().forEach item@{ item ->
                    if (item.has("rawAdData") || item.optBoolean("isYkAd") ||
                        item.optBoolean("ad_flag") || item.has("ku_ad_type")) return@item
                    if (item.optString("action_type") !in setOf("JUMP_TO_SHOW", "JUMP_TO_VIDEO")) return@item
                    add(out, item.optString("title"), item.optString("desc"), item.optString("hImg"), item.optString("img"))
                }
            }
        }
        out
    }

    fun mango(text: String): List<Shelf.Item> = safely {
        val root = JSONObject(text)
        if (root.optInt("code") != 200) return@safely emptyList()
        val out = ArrayList<Shelf.Item>()
        root.optJSONArray("data").objects().forEach { module ->
            if (module.optString("moduleType") != "banner" || module.optLong("adId", -1) != 0L) return@forEach
            module.optJSONArray("moduleData").objects().forEach item@{ item ->
                if (item.optString("jumpKind") !in setOf("1", "12") ||
                    !item.optString("jumpId").matches(Regex("[1-9][0-9]{0,19}")) ||
                    item.optBoolean("isAd") || item.optLong("adId", 0) != 0L) return@item
                val image = httpsImage(item.optString("imgHUrl")) ?: return@item
                val host = runCatching { URI(image).host }.getOrNull().orEmpty()
                if (!Regex("[0-9]vimg\\.hitv\\.com").matches(host)) return@item
                add(out, item.optString("name"), item.optString("subName"), image, requireHd = true)
            }
        }
        out
    }

    fun tencent(text: String): List<Shelf.Item> = safely {
        val root = JSONObject(text)
        if (root.optInt("ret", -1) != 0) return@safely emptyList()
        val out = ArrayList<Shelf.Item>()
        root.optJSONObject("data")?.optJSONArray("CardList").objects().forEach { module ->
            if (module.optString("type") != "pc_carousel") return@forEach
            // Explicitly choose list, excluding the sibling ad_list returned by the API.
            module.optJSONObject("children_list")?.optJSONObject("list")?.optJSONArray("cards").objects().forEach item@{ card ->
                val item = card.optJSONObject("params") ?: return@item
                if (item.optString("cid").isBlank() || item.has("ad_type") || item.has("advertiser_name")) return@item
                // These are actual returned programme-art fields; larger-looking logo keys are excluded.
                add(out, item.optString("title"), item.optString("sub_title"),
                    item.optString("pic_1920x800"), item.optString("pic_1280x720"), requireHd = true)
            }
        }
        out
    }

    /** Public web ranking, not an authenticated or TV-specific recommendation list. */
    fun iqiyi(html: String): List<Shelf.Item> = safely {
        val rendered = html.replace(Regex("<script\\b[^>]*>.*?(?:</script\\s*>|$)|<!--.*?(?:-->|$)",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        val out = ArrayList<Shelf.Item>()
        Regex("<a\\b([^>]*)>(.*?)</a\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(rendered).forEach { match ->
                val attributes = match.groupValues[1]
                val classes = attribute(attributes, "class").split(Regex("\\s+"))
                if ("rvi__box" !in classes || "rvi__box1" !in classes) return@forEach
                // Accept programme cards, not advertising or arbitrary page links.
                val href = attribute(attributes, "href")
                if (!Regex("(?:https?:)?//www\\.iqiyi\\.com/v_[A-Za-z0-9]+\\.html").matches(href)) return@forEach
                val card = match.groupValues[2]
                val picture = Regex("<picture\\b[^>]*>", RegexOption.IGNORE_CASE).find(card)?.value ?: return@forEach
                val image = IqiyiArtworkPolicy.landscapeCandidate(attribute(picture, "id")) ?: return@forEach
                add(out, classAttribute(card, "rvi__tit1", "title"), classAttribute(card, "rvi__des1", "title"), image)
            }
        out
    }

    private fun classAttribute(html: String, className: String, name: String): String =
        Regex("<(?:div|p)\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html)
            .firstOrNull { className in attribute(it.value, "class").split(Regex("\\s+")) }
            ?.let { attribute(it.value, name) }.orEmpty()

    private fun attribute(tag: String, name: String): String {
        val value = Regex("(?<![A-Za-z0-9_:-])${Regex.escape(name)}\\s*=\\s*([\"'])(.*?)\\1",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(tag)?.groupValues?.get(2).orEmpty()
        return value.replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    }

    private fun add(out: MutableList<Shelf.Item>, title: String, meta: String, vararg pictures: String,
                    requireHd: Boolean = false) {
        val name = title.trim().take(ShelfLimits.MAX_TITLE)
        if (out.size >= ShelfLimits.MAX_ITEMS || name.isEmpty() || out.any { it.title == name }) return
        val images = pictures.mapNotNull(::httpsImage).distinct()
        val image = images.firstOrNull() ?: return
        out.add(Shelf.Item(name, meta.trim().take(ShelfLimits.MAX_META), image,
            if (requireHd) images.drop(1) else emptyList(), requireHd))
    }

    private fun httpsImage(raw: String): String? {
        val url = raw.trim().let {
            when {
                it.startsWith("//") -> "https:$it"
                it.startsWith("http://") -> "https://" + it.removePrefix("http://")
                else -> it
            }
        }
        return url.takeIf { it.startsWith("https://") && ShelfLimits.isHttpUrl(it) }
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else
        (0 until length()).mapNotNull { optJSONObject(it) }

    private inline fun safely(parse: () -> List<Shelf.Item>): List<Shelf.Item> = try {
        parse()
    } catch (_: Exception) {
        emptyList()
    }
}
