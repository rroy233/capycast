package com.roy233.tvcast.data.bili

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePlayUrlResolverTest {

    @Test
    fun `parses v2 fmp4 hevc and avc routes using composed official URLs`() {
        val data = JsonParser.parseString(
            """{
                "quality":10000,
                "accept_quality":[10000,400,250,150,80],
                "live_stream_v2":[
                  {"format_name":"fmp4","codec":[
                    {"codec_name":"hevc","current_qn":10000,"width":1920,"height":1080,
                     "bandwidth":4800000,"base_url":"/live-bvc/stream-hevc.m3u8",
                     "url_info":[
                       {"host":"https://cdn-a.bilivideo.com","extra":"?token=secret-a"},
                       {"host":"https://cdn-b.bilivideo.com","extra":"?token=secret-b"}
                     ]},
                    {"codec_name":"avc","current_qn":10000,"width":1920,"height":1080,
                     "base_url":"/live-bvc/stream-avc.m3u8",
                     "url_info":[{"host":"https://cdn-c.bilivideo.com","extra":"?token=secret-c"}]}
                  ]}
                ]
            }""",
        ).asJsonObject

        val live = LivePlayUrlResolver.parse(data, roomId = 23459804L)

        assertEquals(10000, live.quality)
        assertEquals(listOf(10000, 400, 250, 150, 80), live.acceptedQualities)
        assertEquals(3, live.qualityCandidates.size)
        assertEquals("fmp4", live.qualityCandidates.first().format)
        assertEquals(12, live.qualityCandidates.first().codecid)
        assertEquals("https://cdn-a.bilivideo.com/live-bvc/stream-hevc.m3u8?token=secret-a", live.qualityCandidates.first().url)
        assertEquals(1920, live.qualityCandidates.first().width)
        assertTrue(live.qualityCandidates.all { it.quality == 10000 })
    }

    @Test
    fun `parses legacy live stream and durl backup routes`() {
        val data = JsonParser.parseString(
            """{
                "quality":10000,
                "live_stream":[
                  {"format_name":"flv","codec_name":"avc","current_qn":10000,
                   "base_url":"/live-bvc/legacy.flv",
                   "url_info":[{"host":"https://legacy.bilivideo.com","extra":"?sig=x"}]}
                ],
                "durl":[
                  {"url":"https://durl-a.bilivideo.com/live.flv?sig=a",
                   "backup_url":["https://durl-b.bilivideo.com/live.flv?sig=b"]}
                ]
            }""",
        ).asJsonObject

        val live = LivePlayUrlResolver.parse(data, roomId = 88L)

        assertEquals(3, live.qualityCandidates.size)
        assertTrue(live.qualityCandidates.all { it.format == "flv" })
        assertEquals(7, live.qualityCandidates.first().codecid)
        assertTrue(live.qualityCandidates.any { it.url.contains("legacy.bilivideo.com") })
        assertTrue(live.qualityCandidates.any { it.url.contains("durl-b.bilivideo.com") })
    }

    @Test
    fun `AVC fallback filters out HEVC candidates at unchanged quality`() {
        val data = JsonParser.parseString(
            """{
                "quality":10000,
                "live_stream_v2":[
                  {"format_name":"fmp4","codec":[
                    {"codec_name":"hevc","base_url":"https://hevc.bilivideo.com/live.m3u8"},
                    {"codec_name":"avc","base_url":"https://avc.bilivideo.com/live.m3u8"}
                  ]}
                ]
            }""",
        ).asJsonObject

        val live = LivePlayUrlResolver.parse(data, roomId = 88L, forceAvc = true)

        assertEquals(10000, live.quality)
        assertEquals(1, live.qualityCandidates.size)
        assertEquals(7, live.qualityCandidates.single().codecid)
        assertFalse(live.qualityCandidates.single().codecName == "HEVC")
    }

    @Test
    fun `HEVC stream path overrides stale AVC codec metadata`() {
        val data = JsonParser.parseString(
            """{
                "quality":10000,
                "live_stream_v2":[
                  {"format_name":"fmp4","codec":[
                    {"codec_name":"avc","codecid":7,
                     "base_url":"https://cdn.bilivideo.com/live-bvc/live_prohevc/index.m3u8"}
                  ]}
                ]
            }""",
        ).asJsonObject

        val live = LivePlayUrlResolver.parse(data, roomId = 88L)

        assertEquals(12, live.qualityCandidates.single().codecid)
        assertEquals("HEVC", live.qualityCandidates.single().codecName)
    }

    @Test
    fun `fallback qualities use accepted tiers or standard ladder when omitted`() {
        assertEquals(
            listOf(400, 250, 150),
            LivePlayUrlResolver.fallbackQualities(10000, listOf(10000, 250, 400, 150)),
        )
        assertEquals(
            listOf(400, 250, 150, 80),
            LivePlayUrlResolver.fallbackQualities(10000, emptyList()),
        )
        assertTrue(LivePlayUrlResolver.fallbackQualities(80, emptyList()).isEmpty())
    }

    @Test(expected = IllegalStateException::class)
    fun `reports explicit failure when TV response has no stream candidates`() {
        LivePlayUrlResolver.parse(
            JsonParser.parseString("""{"quality":10000,"accept_quality":[10000]}""").asJsonObject,
            roomId = 88L,
        )
    }
}
