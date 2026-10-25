package com.roy233.tvcast.data.bili

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PgcPlaybackResolverTest {

    @Test
    fun `finds playable payload in all supported response wrappers`() {
        val playable = "\"dash\":{\"video\":[{\"baseUrl\":\"https://cdn.bilivideo.com/v.m4s\"}]}"
        val wrappers = listOf(
            "{\"code\":0,\"result\":{\"video_info\":{$playable}}}",
            "{\"code\":0,\"result\":{$playable}}",
            "{\"code\":0,\"data\":{\"video_info\":{$playable}}}",
            "{\"code\":0,\"data\":{$playable}}",
        )

        wrappers.forEach { raw ->
            val parsed = PgcPlaybackResolver.parsePlayurlPayload(raw)
            assertEquals(0, parsed.code)
            assertTrue(parsed.play?.objectOrNull("dash")?.arrayOrNull("video")?.size() == 1)
        }
    }

    @Test
    fun `null optional dash objects and empty video list are not mistaken for media`() {
        val parsed = PgcPlaybackResolver.parsePlayurlPayload(
            """{"code":0,"data":{"video_info":{"dash":{"video":[],"dolby":null,"flac":null},"durl":null}}}""",
        )

        assertNull(parsed.play)
    }

    @Test
    fun `null primary url and nullable track fields can use a returned backup address`() {
        val parsed = PgcPlaybackResolver.parsePlayurlPayload(
            """{"code":0,"data":{"dash":{"video":[{"id":null,"codecid":null,"baseUrl":null,"backup_url":["https://cdn.bilivideo.com/video.m4s"]}]}}}""",
        )

        assertEquals("https://cdn.bilivideo.com/video.m4s", parsed.play
            ?.objectOrNull("dash")?.arrayOrNull("video")?.firstOrNull()?.asJsonObject
            ?.let(CdnCandidateSelector::parseTrack)?.url)
    }

    @Test
    fun `legacy durl wrapper remains a playable payload`() {
        val parsed = PgcPlaybackResolver.parsePlayurlPayload(
            """{"code":0,"result":{"durl":[{"url":"https://cdn.bilivideo.com/v.mp4"}]}}""",
        )

        assertTrue(parsed.play?.arrayOrNull("durl")?.size() == 1)
    }

    @Test
    fun `highest returned video representation keeps the server quality ladder`() {
        val low = track(id = 80, width = 1920, height = 1080, codec = 7)
        val highAvc = track(id = 120, width = 3840, height = 2160, codec = 7)
        val highHevc = track(id = 120, width = 3840, height = 2160, codec = 12)
        val unknown = track(id = 127, width = 7680, height = 4320, codec = 99)

        assertSame(
            highHevc,
            PgcPlaybackResolver.selectHighestVideoTrack(listOf(low, highAvc, highHevc, unknown)),
        )
    }

    @Test
    fun `playurl params request highest web quality and four k`() {
        val params = PgcPlaybackResolver.buildPlayurlParams(
            epId = 468955L,
            seasonId = 41012L,
            aid = 593217401L,
            bvid = "BV1xx411c7mD",
            cid = 2L,
        )

        assertEquals("468955", params["ep_id"])
        assertEquals("127", params["qn"])
        assertEquals("12240", params["fnval"])
        assertEquals("1", params["fourk"])
        assertEquals("41012", params["season_id"])
        assertEquals("1", params["try_look"])
        assertEquals("pre-load", params["gaia_source"])
        assertEquals("true", params["isGaiaAvoided"])
        assertEquals("1315873", params["web_location"])
    }

    private fun track(id: Int, width: Int, height: Int, codec: Int) = BiliApi.Track(
        id = id,
        codecid = codec,
        width = width,
        height = height,
        bandwidth = 1_000_000L,
        url = "https://cdn.bilivideo.com/$id-$codec.m4s",
        backup = emptyList(),
    )
}
