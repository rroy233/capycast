package com.roy233.tvcast.data.bili

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class BiliCastCollectionRepositoryTest {
    @Test
    fun `parses collection sections and current video pages in API order`() {
        val data =
            JsonParser.parseString(
                    """{
          "pages":[{"cid":10},{"cid":11}],
          "ugc_season":{"sections":[
            {"episodes":[{"aid":1,"cid":10,"bvid":"BV1","title":"one"}]},
            {"episodes":[{"aid":2,"cid":20,"bvid":"BV2","title":"two"}]}
          ]}}
        """,
                )
                .asJsonObject
        val collection = parseCollection(data, 1)!!
        assertEquals(listOf(1L, 2L), collection.videos.map { it.aid })
        assertEquals(11L, collection.next(1, 10)?.cid)
        assertEquals(2L, collection.next(1, 11)?.aid)
    }

    @Test
    fun `plain videos have no collection`() {
        assertNull(parseCollection(JsonParser.parseString("{}").asJsonObject, 1))
    }
}
