package com.roy233.tvcast.ui.tv.home

import com.roy233.tvcast.feature.cast.domain.RecentCast
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentCastTimeTest {

    private val now = 1_700_000_000_000L

    @Test
    fun formatsRelativeCastTimeAcrossTheRequestedRanges() {
        assertEquals("刚刚", recent(at = now - 30_000L).relativeTimeLabel(now))
        assertEquals("12分钟前", recent(at = now - 12 * 60_000L).relativeTimeLabel(now))
        assertEquals("3小时前", recent(at = now - 3 * 60 * 60_000L).relativeTimeLabel(now))
        assertEquals("昨天", recent(at = now - 24 * 60 * 60_000L).relativeTimeLabel(now))
        assertEquals("3天前", recent(at = now - 3 * 24 * 60 * 60_000L).relativeTimeLabel(now))
        assertEquals("好久之前", recent(at = now - 8 * 24 * 60 * 60_000L).relativeTimeLabel(now))
    }

    @Test
    fun treatsMissingTimestampsAsLongAgo() {
        assertEquals("好久之前", recent(at = 0L).relativeTimeLabel(now))
    }

    private fun recent(at: Long) = RecentCast(aid = 1L, cid = 10L, title = "测试", at = at)
}
