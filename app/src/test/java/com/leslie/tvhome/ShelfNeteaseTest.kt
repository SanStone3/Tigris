package com.leslie.tvhome

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfNeteaseTest {
    private fun fixture() = javaClass.getResource("/shelf/netease-top-mv.json")!!.readText()

    private fun response(vararg rows: JSONObject) = JSONObject().put("code", 200)
        .put("data", JSONArray(rows.toList())).toString()

    private fun row(id: Any = 1, name: Any = "MV", cover: Any = "http://p1.music.126.net/cover/original.jpg") =
        JSONObject().put("id", id).put("name", name).put("artistName", "Artist").put("cover", cover)

    @Test fun parsesTheAnonymousOfficialChartInRankingOrder() {
        val items = ShelfNetease.topMv(fixture())
        assertEquals(12, items.size)
        assertEquals(listOf("时光洪流", "自由的你", "一张相片"), items.take(3).map { it.title })
        assertEquals("G.E.M.邓紫棋", items[1].meta)
        assertEquals("https://p1.music.126.net/fqi6nqeI00_eGPk5uZSY2w==/109951174144804614.jpg", items[2].image)
        assertTrue(items.all { it.requireFullHdArtwork && it.fallbackImages.isEmpty() })
        assertTrue(items.all { it.image.startsWith("https://p1.music.126.net/") && '?' !in it.image })
        assertEquals("https://music.163.com/api/mv/toplist?limit=12&offset=0", ShelfNetease.TOP_MV_URL)
    }

    @Test fun apiPictureNamesAndVideoResolutionsCannotBypassNativeImageChecks() {
        val items = ShelfNetease.topMv(fixture())
        val square = items.single { it.title == "薛定谔的雨" }
        assertTrue(square.requireFullHdArtwork)
        // This actual cover is square despite the API's pic16v9 picture ID.
        assertFalse(ShelfLimits.isFullHdLandscape(2160, 2160))
        assertFalse(ShelfLimits.isFullHdLandscape(1738, 978))
        assertTrue(ShelfLimits.isFullHdLandscape(2785, 1567))
        assertTrue(ShelfLimits.isFullHdLandscape(1920, 1080))
        val onlyVideo = row().apply {
            remove("cover")
            put("mv", JSONObject().put("pic16v9", "109951174144804614")
                .put("videos", JSONArray().put(JSONObject().put("width", 3840).put("height", 2160))))
        }
        assertTrue(ShelfNetease.topMv(response(onlyVideo)).isEmpty())
    }

    @Test fun changedOrFailedResponsesYieldNoShelf() {
        for (text in listOf("", "<html>unavailable</html>", "null", "[]", "{}",
            """{"code":301,"data":[]}""", """{"code":200,"data":{}}""")) {
            assertTrue(ShelfNetease.topMv(text).isEmpty())
        }
        assertTrue(ShelfNetease.topMv(response(row(name = JSONObject()), row(name = " "),
            row(id = 0), row(id = -1), row(id = 1.5), row(id = "not-an-id"))).isEmpty())
    }

    @Test fun acceptsOnlyReturnedOfficialCoversWithoutSynthesizingArtwork() {
        val invalid = listOf("javascript:bad()", "file:///image.jpg", "https://ads.example/image.jpg",
            "https://p1.music.126.net.evil.example/image.jpg", "https://p1.music.126.net@ads.example/image.jpg",
            "https://user@p1.music.126.net/image.jpg", "https://p1.music.126.net:8443/image.jpg")
        assertTrue(ShelfNetease.topMv(response(*invalid.mapIndexed { index, cover ->
            row(id = index + 1, cover = cover)
        }.toTypedArray())).isEmpty())
        val returned = "https://p2.music.126.net/returned/image.jpg?existing=1"
        val item = ShelfNetease.topMv(response(row(cover = returned))).single()
        assertEquals(returned, item.image)
        assertTrue(item.fallbackImages.isEmpty())
    }

    @Test fun capsDeduplicatesByMvIdAndBoundsDisplayText() {
        val rows = ArrayList<JSONObject>()
        repeat(20) { index ->
            val item = row(id = index + 1, name = "MV $index")
            rows.add(item)
            rows.add(JSONObject(item.toString()))
        }
        val items = ShelfNetease.topMv(response(*rows.toTypedArray()))
        assertEquals(ShelfLimits.MAX_ITEMS, items.size)
        assertEquals("MV 11", items.last().title)
        val long = row(name = "x".repeat(200)).put("artistName", "y".repeat(300))
        val item = ShelfNetease.topMv(response(long)).single()
        assertEquals(ShelfLimits.MAX_TITLE, item.title.length)
        assertEquals(ShelfLimits.MAX_META, item.meta.length)
    }
}
