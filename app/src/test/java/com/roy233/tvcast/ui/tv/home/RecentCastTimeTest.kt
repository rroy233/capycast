package com.roy233.tvcast.ui.tv.home

import com.roy233.tvcast.feature.cast.domain.RecentCast
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentCastTimeTest {

    private val now = 1_700_000_000_000L

    @Test
    fun formatsRelativeCastTimeAcrossTheRequestedRanges() {
        assertEquals(
            RelativeCastTime(RelativeTimeUnit.JUST_NOW),
            recent(at = now - 30_000L).relativeTime(now),
        )
        assertEquals(
            RelativeCastTime(RelativeTimeUnit.MINUTES, 12),
            recent(at = now - 12 * 60_000L).relativeTime(now),
        )
        assertEquals(
            RelativeCastTime(RelativeTimeUnit.HOURS, 3),
            recent(at = now - 3 * 60 * 60_000L).relativeTime(now),
        )
        assertEquals(
            RelativeCastTime(RelativeTimeUnit.YESTERDAY),
            recent(at = now - 24 * 60 * 60_000L).relativeTime(now),
        )
        assertEquals(
            RelativeCastTime(RelativeTimeUnit.DAYS, 3),
            recent(at = now - 3 * 24 * 60 * 60_000L).relativeTime(now),
        )
        assertEquals(
            RelativeCastTime(RelativeTimeUnit.LONG_AGO),
            recent(at = now - 8 * 24 * 60 * 60_000L).relativeTime(now),
        )
    }

    @Test
    fun treatsMissingTimestampsAsLongAgo() {
        assertEquals(RelativeCastTime(RelativeTimeUnit.LONG_AGO), recent(at = 0L).relativeTime(now))
    }

    private fun recent(at: Long) = RecentCast(aid = 1L, cid = 10L, title = "测试", at = at)
}
