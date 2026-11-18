package com.roy233.tvcast.feature.subtitle.presentation

import com.roy233.tvcast.feature.subtitle.application.SubtitleAvailability
import com.roy233.tvcast.feature.subtitle.application.SubtitleSessionState
import com.roy233.tvcast.feature.subtitle.domain.SubtitleContent
import com.roy233.tvcast.ui.model.SubtitleSummaryKind
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleTrackMapperTest {
    @Test
    fun summaryReportsSemanticAvailabilityWithoutEmbeddingDisplayText() {
        val state =
            SubtitleSessionState(
                content = SubtitleContent(aid = 1, cid = 2),
                tracks = emptyList(),
                selected = null,
                availability = SubtitleAvailability.LOADING,
                enabled = true,
            )
        assertEquals(SubtitleSummaryKind.LOADING, SubtitleTrackMapper.summary(state).kind)
        assertEquals(
            SubtitleSummaryKind.UNAVAILABLE,
            SubtitleTrackMapper.summary(state.copy(availability = SubtitleAvailability.UNAVAILABLE))
                .kind,
        )
        assertEquals(
            SubtitleSummaryKind.EMPTY,
            SubtitleTrackMapper.summary(state.copy(availability = SubtitleAvailability.EMPTY)).kind,
        )
        assertEquals(
            SubtitleSummaryKind.DISABLED,
            SubtitleTrackMapper.summary(state.copy(enabled = false)).kind,
        )
    }
}
