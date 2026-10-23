package com.roy233.tvcast.data.bili

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CdnCandidateSelectorTest {

    @Test
    fun `marks qn 125 HEVC representation as HDR`() {
        val track = CdnCandidateSelector.parseTrack(
            JsonParser.parseString(
                """{
                    "id":125,"codecid":12,"width":3840,"height":2160,
                    "mimeType":"video/mp4","codecs":"hev1.2.4.L153.B0",
                    "baseUrl":"https://upos.example.com/hdr.m4s"
                }""",
            ).asJsonObject,
        )

        assertTrue(track.isHdr)
        assertFalse(track.isDolbyVision)
        assertEquals("video/mp4", track.mimeType)
    }

    @Test
    fun `marks Dolby Vision representation for Media3 routing`() {
        val track = CdnCandidateSelector.parseTrack(
            JsonParser.parseString(
                """{
                    "id":126,"codecid":12,"width":3840,"height":2160,
                    "mime_type":"video/mp4","codecs":"dvhe.05.06",
                    "base_url":"https://upos.example.com/dv.m4s"
                }""",
            ).asJsonObject,
        )

        assertTrue(track.isHdr)
        assertTrue(track.isDolbyVision)
        assertEquals("dvhe.05.06", track.codecs)
    }

    @Test
    fun `prefers Dolby audio when the response explicitly provides it`() {
        val aac = CdnCandidateSelector.parseTrack(
            JsonParser.parseString(
                """{"id":30280,"codecid":0,"baseUrl":"https://upos.example.com/aac.m4s"}""",
            ).asJsonObject,
        )
        val dolby = CdnCandidateSelector.parseTrack(
            JsonParser.parseString(
                """{"id":30250,"codecid":0,"codecs":"ec-3","baseUrl":"https://upos.example.com/eac3.m4s"}""",
            ).asJsonObject,
            isDolbyAudio = true,
        )

        assertEquals(dolby, CdnCandidateSelector.pickAudio(listOf(aac, dolby)))
        assertTrue(dolby.isDolbyAudio)
    }
}
