package com.roy233.tvcast.data.bili

import org.junit.Assert.assertEquals
import org.junit.Test

class WbiSignerTest {

    @Test
    fun `matches BiliPai WBI encoding filtering ordering and digest`() {
        val signed = WbiSigner.signWithKey(
            params = mapOf("z" to "last", "a" to "hello world!*'()~"),
            mixinKey = "0123456789abcdef0123456789abcdef",
            timestampSeconds = 123L,
        )

        assertEquals("hello world~", signed["a"])
        assertEquals("last", signed["z"])
        assertEquals("123", signed["wts"])
        assertEquals("8d2649017f01d1a9df440c23b09a6e6f", signed["w_rid"])
    }

    @Test
    fun `derives the first 32 mixin characters with BiliPai index table`() {
        assertEquals(
            "UVsc1ixGpYkF6dTJBRfXHjQtDCoNmMPn",
            WbiSigner.mixinKeyFrom(
                imgKey = "abcdefghijklmnopqrstuvwxyz",
                subKey = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_",
            ),
        )
    }
}
