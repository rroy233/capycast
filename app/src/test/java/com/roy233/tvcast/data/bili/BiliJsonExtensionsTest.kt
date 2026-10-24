package com.roy233.tvcast.data.bili

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BiliJsonExtensionsTest {

    @Test
    fun `treats null optional audio as absent`() {
        val dash = JsonParser.parseString("""{"dolby":{"audio":null}}""").asJsonObject

        assertNull(dash.objectOrNull("dolby")?.arrayOrNull("audio"))
    }

    @Test
    fun `treats a null optional container as absent`() {
        val dash = JsonParser.parseString("""{"dolby":null}""").asJsonObject

        assertNull(dash.objectOrNull("dolby"))
    }

    @Test
    fun `returns an actual array response member`() {
        val dash = JsonParser.parseString("""{"dolby":{"audio":[{"id":30250}]}}""").asJsonObject

        assertEquals(1, dash.objectOrNull("dolby")?.arrayOrNull("audio")?.size())
    }
}
