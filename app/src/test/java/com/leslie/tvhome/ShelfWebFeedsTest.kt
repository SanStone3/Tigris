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
        val mango = ShelfWebFeeds.mango(fixture("mango-featured.json"))
        val tencent = ShelfWebFeeds.tencent(fixture("tencent-featured.json"))
        assertEquals(listOf("八仙！", "谷雨", "嫁金钗"), youku.map { it.title })
        assertEquals(listOf("今天是太阳雨☀爽看3集", "玉簟秋🍁大结局", "不想睡的星期五·第11期"), mango.map { it.title })
        assertEquals(listOf("八仙！", "走马上任"), tencent.map { it.title })
        assertTrue(tencent.all { "pic_1920x800" in it.image && it.requireHdArtwork })
        assertTrue(tencent.all { it.fallbackImages.size == 1 && "pic_1280x720" in it.fallbackImages.single() })
        (youku + mango + tencent).forEach {
            assertTrue(it.image.startsWith("https://"))
            assertTrue(ShelfLimits.isHttpUrl(it.image))
            assertTrue(it.title.isNotBlank())
        }
        assertTrue(mango.all { it.requireFullHdArtwork && it.image.contains("vimg.hitv.com/") })
        assertTrue(youku.all { it.requireFullHdArtwork && it.fallbackImages.isEmpty() })
    }

    @Test fun brokenOrChangedSourcesProduceNoShelf() {
        for (body in listOf("", "<html>unavailable</html>", "{}", "[]", "null")) {
            assertTrue(ShelfWebFeeds.youku(body).isEmpty())
            assertTrue(ShelfWebFeeds.mango(body).isEmpty())
            assertTrue(ShelfWebFeeds.tencent(body).isEmpty())
            assertTrue(ShelfWebFeeds.iqiyi(body).isEmpty())
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
                {"params":{"cid":"film","title":"Film","pic_1920x800":"//media.example/film.jpg"}},
                {"params":{"cid":"ad","title":"Ad","ad_type":"plain","image_url":"https://media.example/a.jpg"}},
                {"params":{"title":"No programme","image_url":"https://media.example/b.jpg"}}
              ]}
            }}]}}
        """.trimIndent()
        assertEquals(listOf("Film"), ShelfWebFeeds.tencent(body).map { it.title })
    }

    @Test fun youkuTvArtworkNeverFallsBackToThePortraitField() {
        val html = """<script>window.__INITIAL_DATA__ = {"moduleList":[{"typeName":"KU_FLIX_LUNBO_V2","components":[{"itemList":[
            {"title":"Portrait only","action_type":"JUMP_TO_SHOW","img":"https://media.example/portrait.jpg"},
            {"title":"Wide programme","action_type":"JUMP_TO_SHOW","hImg":"https://media.example/wide.jpg","img":"https://media.example/portrait.jpg"}
        ]}]}]};</script>"""
        val item = ShelfWebFeeds.youku(html).single()
        assertEquals("Wide programme", item.title)
        assertTrue(item.requireFullHdArtwork)
        assertTrue(item.fallbackImages.isEmpty())
    }

    @Test fun tencentUsesOnlyReturnedProgrammeArtworkAndKeepsOneHdFallback() {
        fun body(params: String) = """{"ret":0,"data":{"CardList":[{"type":"pc_carousel","children_list":{"list":{"cards":[{"params":$params}]}}}]}}"""
        val item = ShelfWebFeeds.tencent(body("""{"cid":"film","title":"Film",
            "pic_1920x800":"//media.example/wide.jpg","pic_1280x720":"https://media.example/hd.jpg",
            "pic_ori_2x1":"https://media.example/transparent-logo.png",
            "ready_image_url":"https://media.example/unknown-size.jpg","image_url":"https://media.example/small.jpg"}""")).single()
        assertEquals("https://media.example/wide.jpg", item.image)
        assertEquals(listOf("https://media.example/hd.jpg"), item.fallbackImages)
        assertTrue(item.requireHdArtwork)
        val fallbackOnly = ShelfWebFeeds.tencent(body("""{"cid":"film","title":"Film",
            "pic_1920x800":"javascript:bad()","pic_1280x720":"https://media.example/hd.jpg"}""")).single()
        assertEquals("https://media.example/hd.jpg", fallbackOnly.image)
        assertTrue(ShelfWebFeeds.tencent(body("""{"cid":"film","title":"Film",
            "image_url":"https://media.example/small.jpg","pic_ori_2x1":"https://media.example/logo.png"}""")).isEmpty())
    }

    @Test fun capsDeduplicatesAndSkipsMalformedItems() {
        val docs = JSONArray()
            .put(JSONObject().put("name", "Bad").put("jumpId", "1").put("jumpKind", "1").put("imgHUrl", "file:///bad.jpg"))
            .put(JSONObject().put("name", "").put("jumpId", "1").put("jumpKind", "1").put("imgHUrl", "https://1vimg.hitv.com/empty.jpg"))
        repeat(20) { i ->
            val item = JSONObject().put("name", "Film $i").put("jumpId", (i + 1).toString())
                .put("jumpKind", "1").put("imgHUrl", "https://1vimg.hitv.com/$i.jpg")
            docs.put(item)
            docs.put(JSONObject(item.toString()))
        }
        val module = JSONObject().put("moduleType", "banner").put("adId", 0).put("moduleData", docs)
        val body = JSONObject().put("code", 200).put("data", JSONArray().put(module)).toString()
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
        val mangoQuery = java.net.URI(ShelfWebFeeds.MANGO_URL).query.split('&').map { it.substringBefore('=') }.toSet()
        assertEquals(setOf("vclassId", "type", "pageNo", "pageSize"), mangoQuery)
    }

    @Test fun mangoHomepageExcludesAdsExternalJumpsAndUnverifiedImageFields() {
        val root = JSONObject(fixture("mango-featured.json"))
        val module = root.getJSONArray("data").getJSONObject(0)
        module.put("adId", 9)
        assertTrue(ShelfWebFeeds.mango(root.toString()).isEmpty())
        module.put("adId", 0).put("moduleType", "other")
        assertTrue(ShelfWebFeeds.mango(root.toString()).isEmpty())
        module.put("moduleType", "banner")
        val valid = module.getJSONArray("moduleData").getJSONObject(0)
        val cases = JSONArray()
        cases.put(JSONObject(valid.toString()).put("name", "Ad").put("adId", 5))
        cases.put(JSONObject(valid.toString()).put("name", "External").put("jumpKind", "99"))
        cases.put(JSONObject(valid.toString()).put("name", "No programme").put("jumpId", "not-a-programme"))
        cases.put(JSONObject(valid.toString()).put("name", "Other host").put("imgHUrl", "https://ads.example/image.jpg"))
        val portrait = JSONObject(valid.toString()).put("name", "Portrait only").put("imgHVUrl", valid.getString("imgHUrl"))
        portrait.remove("imgHUrl")
        cases.put(portrait).put(valid)
        module.put("moduleData", cases)
        val item = ShelfWebFeeds.mango(root.toString()).single()
        assertEquals(valid.getString("name"), item.title)
        assertEquals(valid.getString("imgHUrl"), item.image)
        assertTrue(item.requireFullHdArtwork)
        assertTrue(ShelfWebFeeds.mango("""{"code":200,"data":{"hitDocs":[{"title":"Old small image","imgUrlH":"https://1vimg.hitv.com/small.jpg"}]}}""").isEmpty())
    }

    @Test fun parsesRealPublicRankingCardsWithDocumentedLandscapeCandidates() {
        val items = ShelfWebFeeds.iqiyi(fixture("iqiyi-ranks.html"))
        assertEquals(listOf("小城良方", "折月亮", "伟大的长征"), items.map { it.title })
        assertEquals("纯过日子，嘎嘎回血！", items.first().meta)
        assertEquals(listOf(
            "https://pic6.iqiyipic.com/image/20261102/1e/06/a_100692840_m_601_m8_1920_1080.jpg",
            "https://pic8.iqiyipic.com/image/20261103/4d/10/a_100671203_m_601_m6_1920_1080.jpg",
            "https://pic5.iqiyipic.com/image/20261009/f0/68/a_100688277_m_601_m6_1920_1080.jpg",
        ), items.map { it.image })
        assertTrue(items.all { IqiyiArtworkPolicy.isLandscapeCandidate(it.image) })
    }

    @Test fun rankingParserDoesNotReadAdvertisingScriptsOrUnknownArtwork() {
        val card = """
            <a class="rvi__box rvi__box1 rvi__box--hover" href="//www.iqiyi.com/v_example.html">
              <picture id="//pic6.iqiyipic.com/image/20261102/1e/06/a_100692840_m_601_m8_260_360.jpg"></picture>
              <div class="rvi__tit1" title="A &amp; B &quot;Film&quot;"></div><p title="Description" class="rvi__des1"></p>
            </a>
        """.trimIndent()
        val html = "<script>throw new Error('never run'); '$card';</script>" +
            "<!-- $card -->" + card.replace("rvi__box1", "advertisement") +
            card.replace("//www.iqiyi.com/v_example.html", "https://ads.example/banner") +
            card.replace("_260_360.jpg", ".jpg") + card
        val item = ShelfWebFeeds.iqiyi(html).single()
        assertEquals("A & B \"Film\"", item.title)
        assertEquals("Description", item.meta)
        assertTrue(ShelfWebFeeds.iqiyi("<script>'$card'").isEmpty())
    }

    @Test fun changedRankingMarkupReturnsNoItems() {
        val fixture = fixture("iqiyi-ranks.html")
        assertTrue(ShelfWebFeeds.iqiyi(fixture.replace("rvi__tit1", "renamed-title")).isEmpty())
        assertTrue(ShelfWebFeeds.iqiyi(fixture.replace("<picture id=", "<picture data-id=")).isEmpty())
        assertTrue(ShelfWebFeeds.iqiyi(fixture.replace("rvi__box1", "other-card")).isEmpty())
    }

    @Test fun mapsOnlyVerifiedTelevisionPackagesAndPreservesExistingFeeds() {
        assertEquals("youku", Shelf.builtInPackages["com.cibn.tv"])
        assertEquals("iqiyi", Shelf.builtInPackages["com.gitvdemo.video"])
        assertTrue(Shelf.has("com.gitvdemo.video"))
        assertFalse(Shelf.has("com.gitvvideo"))
        assertEquals("tencent", Shelf.builtInPackages["com.ktcp.video"])
        assertEquals("mango", Shelf.builtInPackages["com.hunantv.market"])
        assertEquals("mango", Shelf.builtInPackages["com.hunantv.license"])
        assertEquals("mango", Shelf.builtInPackages["com.starcor.mango"])
        assertEquals("bilibili", Shelf.builtInPackages["com.tutu.myblbl"])
        assertEquals("douyu", Shelf.builtInPackages["com.douyu.xl.douyutv"])
        assertFalse(Shelf.builtInPackages.containsValue("quark"))
    }
}
