package com.roy233.tvcast.data.bili

import com.roy233.tvcast.feature.cast.domain.CastContent
import com.roy233.tvcast.feature.cast.domain.CastContentKind
import org.junit.Assert.assertEquals
import org.junit.Test

class NvaContentKindTest {

    @Test
    fun `PGC identity wins when cast payload also includes aid and cid`() {
        val content = CastContent(
            aid = 593217401L,
            cid = 2L,
            epId = 468955L,
            seasonId = 41012L,
        )

        assertEquals(CastContentKind.PGC, content.kind)
        assertEquals(true, content.hasPgc)
        assertEquals(false, content.hasUgc)
    }

    @Test
    fun `live room identity has precedence over PGC markers`() {
        val content = CastContent(epId = 468955L, seasonId = 41012L, roomId = 23459804L)

        assertEquals(CastContentKind.LIVE, content.kind)
    }

    @Test
    fun `season and episode identities classify as PGC without aid and cid`() {
        assertEquals(
            CastContentKind.PGC,
            CastContent(seasonId = 41012L).kind,
        )
    }
}
