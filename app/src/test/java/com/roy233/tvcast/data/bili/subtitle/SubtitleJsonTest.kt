package com.roy233.tvcast.data.bili.subtitle

import com.roy233.tvcast.feature.subtitle.domain.SubtitleContent
import com.roy233.tvcast.feature.subtitle.domain.SubtitleOrigin
import org.junit.Assert.assertEquals
import org.junit.Test

/** 验证字幕轨道与正文 JSON 的解析与过滤。 */
class SubtitleJsonTest {

    @Test
    fun `tracks keep manual and ai entries and drop untrusted urls`() {
        val raw =
            """
            {"code":0,"data":{"subtitle":{"subtitles":[
              {"id":1,"id_str":"1","lan":"zh-CN","lan_doc":"中文（中国）",
               "subtitle_url":"//aisubtitle.hdslb.com/bfs/subtitle/1.json",
               "ai_status":0,"ai_type":0,"type":0,"is_lock":false},
              {"id":2,"id_str":"2","lan":"zh-Hans","lan_doc":"中文（自动生成）",
               "subtitle_url":"//aisubtitle.hdslb.com/bfs/subtitle/2.json",
               "ai_status":2,"ai_type":1,"type":0,"is_lock":false},
              {"id":3,"id_str":"3","lan":"en-US","lan_doc":"English",
               "subtitle_url":"https://evil.example.com/subtitle/3.json",
               "ai_status":0,"ai_type":0,"type":0,"is_lock":false}
            ]}}}
            """
                .trimIndent()

        val tracks = SubtitleJson.tracks(raw, CONTENT)

        assertEquals(listOf(1L, 2L), tracks.map { it.id })
        assertEquals(SubtitleOrigin.MANUAL, tracks[0].origin)
        assertEquals(SubtitleOrigin.AI_ASSIST, tracks[1].origin)
        assertEquals("https://aisubtitle.hdslb.com/bfs/subtitle/1.json", tracks[0].url)
    }

    @Test
    fun `tracks are empty when the response has no subtitle section`() {
        assertEquals(
            emptyList<Any>(),
            SubtitleJson.tracks("""{"code":-352,"message":"risk"}""", CONTENT),
        )
        assertEquals(
            emptyList<Any>(),
            SubtitleJson.tracks("""{"data":{"subtitle":null}}""", CONTENT),
        )
    }

    @Test
    fun `cues convert seconds to milliseconds and drop blank entries`() {
        val raw =
            """
            {"font_size":0.4,"body":[
              {"from":1.5,"to":3.0,"content":"second"},
              {"from":0.25,"to":1.0,"content":"first"},
              {"from":3.0,"to":3.0,"content":"zero length"},
              {"from":4.0,"to":5.0,"content":"   "}
            ]}
            """
                .trimIndent()

        val cues = SubtitleJson.cues(raw)

        assertEquals(listOf("first", "second", "zero length"), cues.map { it.text })
        assertEquals(250L, cues[0].startMs)
        assertEquals(1_000L, cues[0].endMs)
        assertEquals(3_000L, cues[2].startMs)
        assertEquals(3_100L, cues[2].endMs)
    }

    @Test
    fun `cues are empty for malformed payloads`() {
        assertEquals(emptyList<Any>(), SubtitleJson.cues("not json"))
        assertEquals(emptyList<Any>(), SubtitleJson.cues("""{"body":null}"""))
    }

    private companion object {
        val CONTENT = SubtitleContent(aid = 1L, cid = 2L)
    }
}
