package com.roy233.tvcast.feature.playback.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveQualityMapperTest {

    @Test
    fun `maps live quality identifiers to the same labels`() {
        assertEquals("原画", LiveQualityMapper.label(10_000))
        assertEquals("蓝光", LiveQualityMapper.label(400))
        assertEquals("超清", LiveQualityMapper.label(250))
        assertEquals("高清", LiveQualityMapper.label(150))
        assertEquals("流畅", LiveQualityMapper.label(80))
        assertEquals("直播 qn=1", LiveQualityMapper.label(1))
    }
}
