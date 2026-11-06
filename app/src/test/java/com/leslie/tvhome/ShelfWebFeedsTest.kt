package com.leslie.tvhome

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfWebFeedsTest {
    private fun fixture(name: String) = javaClass.getResource("/shelf/$name")!!.readText()

    @Test fun parsesReducedOfficialResponsesWithoutAdvertisements() {
        // Captured anonymously on 2026-11-06; provenance and verified TV packages
        // are retained in shelf/sources.json. No external network in these tests.
        val youku = ShelfWebFeeds.youku(fixture("youku-home.html"))
        val mango = ShelfWebFeeds.mango(fixture("mango-popular.json"))
        val tencent = ShelfWebFeeds.tencent(fixture("tencent-featured.json"))
        assertEquals(listOf("八仙！", "谷雨", "嫁金钗"), youku.map { it.title })
        assertEquals(listOf("玉簟秋", "小巷人家"), mango.map { it.title })
        assertEquals(listOf("八仙！", "走马上任"), tencent.map { it.title })
        (youku + mango + tencent).forEach {
            assertTrue(it.image.startsWith("https://"))
            assertTrue(ShelfLimits.isHttpUrl(it.image))
            assertTrue(it.title.isNotBlank())
        }
        assertTrue(mango.first().image.contains("202510311138040157719"))
    }

    @Test fun brokenOrChangedSourcesProduceNoShelf() {
        for (body in listOf("", "<html>unavailable</html>", "{}", "[]", "null")) {
            assertTrue(ShelfWebFeeds.youku(body).isEmpty())
            assertTrue(ShelfWebFeeds.mango(body).isEmpty())
            assertTrue(ShelfWebFeeds.tencent(body).isEmpty())
        }
        assertTrue(ShelfWebFeeds.mango("""{"code":404,"data":{"hitDocs":[]}}""").isEmpty())
        assertTrue(ShelfWebFeeds.tencent("""{"ret":-1,"data":{"CardList":[]}}""").isEmpty())
    }

    @Test fun youkuDoesNotExecuteScriptsOrKeepAdsAndArbitraryLinks() {
        val html = """
            <script>window.__INITIAL_DATA__ = {"moduleList":[{"typeName":"KU_FLIX_LUNBO_V2","components":[{"itemList":[
              {"title":"undefined","desc":undefined,"action_type":"JUMP_TO_SHOW","hImg":"http://media.example/good.jpg"},
              {"title":"Ad","isYkAd":true,"action_type":"JUMP_TO_SHOW","hImg":"https://media.example/ad.jpg"},
              {"title":"Raw ad","rawAdData":{},"action_type":"JUMP_TO_VIDEO","img":"https://media.example/ad.jpg"},
              {"title":"Link","action_type":"JUMP_TO_URL","img":"https://media.example/link.jpg"},
              {"title":"Script","action_type":"JUMP_TO_SHOW","img":"javascript:alert(1)"}
            ]}]}]};</script><script>throw new Error('must never execute');</script>
        """.trimIndent()
        val items = ShelfWebFeeds.youku(html)
        assertEquals(listOf("undefined"), items.map { it.title })
        assertEquals("", items.single().meta)
        assertEquals("https://media.example/good.jpg", items.single().image)
    }

    @Test fun tencentOnlyReadsEditorialListAndRequiresAProgramme() {
        val body = """
            {"ret":0,"data":{"CardList":[{"type":"pc_carousel","children_list":{
              "ad_list":{"cards":[{"params":{"cid":"x","title":"Sibling ad","image_url":"https://media.example/a.jpg"}}]},
              "list":{"cards":[
                {"params":{"cid":"film","title":"Film","image_url":"//media.example/film.jpg"}},
                {"params":{"cid":"ad","title":"Ad","ad_type":"plain","image_url":"https://media.example/a.jpg"}},
                {"params":{"title":"No programme","image_url":"https://media.example/b.jpg"}}
              ]}
            }}]}}
        """.trimIndent()
        assertEquals(listOf("Film"), ShelfWebFeeds.tencent(body).map { it.title })
    }

    @Test fun capsDeduplicatesAndSkipsMalformedItems() {
        val docs = JSONArray()
            .put(JSONObject().put("title", "Bad").put("imgUrlH", "file:///bad.jpg"))
            .put(JSONObject().put("title", "").put("imgUrlH", "https://media.example/empty.jpg"))
        repeat(20) { i ->
            docs.put(JSONObject().put("title", "Film $i").put("imgUrlH", "https://media.example/$i.jpg"))
            docs.put(JSONObject().put("title", "Film $i").put("imgUrlH", "https://media.example/duplicate.jpg"))
        }
        val body = JSONObject().put("code", 200).put("data", JSONObject().put("hitDocs", docs)).toString()
        val items = ShelfWebFeeds.mango(body)
        assertEquals(ShelfLimits.MAX_ITEMS, items.size)
        assertEquals("Film 0", items.first().title)
        assertEquals("Film 11", items.last().title)
    }

    @Test fun requestUsesNoAccountIdentity() {
        val request = JSONObject(ShelfWebFeeds.tencentRequest())
        assertEquals("", request.getJSONObject("page_params").getString("ams_cookies"))
        assertEquals("", request.getJSONObject("page_bypass_params").getString("abtest_bypass_id"))
        assertFalse(request.toString().contains("auth_token"))
    }

    @Test fun mapsOnlyVerifiedTelevisionPackagesAndPreservesExistingFeeds() {
        assertEquals("youku", Shelf.builtInPackages["com.cibn.tv"])
        assertFalse(Shelf.has("com.gitvdemo.video"))
        assertEquals("tencent", Shelf.builtInPackages["com.ktcp.video"])
        assertEquals("mango", Shelf.builtInPackages["com.hunantv.market"])
        assertEquals("bilibili", Shelf.builtInPackages["com.tutu.myblbl"])
        assertEquals("douyu", Shelf.builtInPackages["com.douyu.xl.douyutv"])
        assertFalse(Shelf.builtInPackages.containsValue("quark"))
    }
}
