package com.roy233.tvcast.data.bili

import org.junit.Assert.assertEquals
import org.junit.Test

class NvaContentKindTest {

    @Test
    fun `PGC identity wins when cast payload also includes aid and cid`() {
        val content = NvaExtDecoder.NvaExtContent(
            aid = 593217401L,
            cid = 2L,
            eid = 468955L,
            sid = 41012L,
        )

        assertEquals(NvaExtDecoder.ContentKind.PGC, content.contentKind)
        assertEquals(true, content.hasPgc)
        assertEquals(false, content.hasUgc)
    }

    @Test
    fun `live room identity has precedence over PGC markers`() {
        val content = NvaExtDecoder.NvaExtContent(eid = 468955L, sid = 41012L, roomId = 23459804L)

        assertEquals(NvaExtDecoder.ContentKind.LIVE, content.contentKind)
    }

    @Test
    fun `season and episode identities classify as PGC without aid and cid`() {
        assertEquals(
            NvaExtDecoder.ContentKind.PGC,
            NvaExtDecoder.NvaExtContent(sid = 41012L).contentKind,
        )
    }
}
