package com.roy233.tvcast.data.auth

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InternationalQrPollTest {
    private fun parse(value: String) =
        parseInternationalQrPoll(JsonParser.parseString(value).asJsonObject)

    @Test
    fun `web poll maps waiting scan confirmation and expiration from inner status`() {
        for (code in listOf(86101, 86090, 86038)) {
            assertEquals(
                InternationalQrPoll.Pending(code, "waiting"),
                parse("""{"code":0,"data":{"code":$code,"message":"waiting"}}"""),
            )
        }
    }

    @Test
    fun `outer rejection is not mistaken for a successful response`() {
        assertEquals(
            InternationalQrPoll.Failure(48007, "rejected"),
            parse("""{"code":48007,"message":"rejected"}"""),
        )
        assertTrue(parse("""{"code":0,"data":{}}""") is InternationalQrPoll.Failure)
        assertTrue(parse("""{"code":0,"data":{"code":123}}""") is InternationalQrPoll.Failure)
    }

    @Test
    fun `confirmed response retains web refresh token`() {
        assertEquals(
            InternationalQrPoll.Confirmed("refresh"),
            parse("""{"code":0,"data":{"code":0,"refresh_token":"refresh"}}"""),
        )
    }
}
