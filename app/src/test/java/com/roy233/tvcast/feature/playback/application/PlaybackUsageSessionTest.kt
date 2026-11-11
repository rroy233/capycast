package com.roy233.tvcast.feature.playback.application

import com.roy233.tvcast.core.diagnostics.CompletionBucket
import com.roy233.tvcast.core.diagnostics.ContentKind
import com.roy233.tvcast.core.diagnostics.NonFatalReport
import com.roy233.tvcast.core.diagnostics.PlaybackEndReason
import com.roy233.tvcast.core.diagnostics.QualityTelemetry
import com.roy233.tvcast.core.diagnostics.UsageEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackUsageSessionTest {
    @Test
    fun `session logs one start and active duration across pauses`() {
        var now = 1_000L
        val telemetry = RecordingTelemetry()
        val session = PlaybackUsageSession(telemetry) { now }

        session.open(isLive = false)
        session.onPlaying()
        now += 5_000L
        session.onPaused()
        now += 2_000L
        session.onPlaying()
        session.onPlaying()
        now += 3_000L
        session.finish(PlaybackEndReason.COMPLETED, positionMs = 100L, durationMs = 100L)

        assertEquals(
            listOf(
                UsageEvent.PlaybackStarted(ContentKind.VOD),
                UsageEvent.PlaybackSessionEnded(
                    contentKind = ContentKind.VOD,
                    endReason = PlaybackEndReason.COMPLETED,
                    activeDurationSeconds = 8L,
                    completionBucket = CompletionBucket.COMPLETE,
                ),
            ),
            telemetry.events,
        )
    }

    @Test
    fun `opening replacement closes the previous session once`() {
        val telemetry = RecordingTelemetry()
        val session = PlaybackUsageSession(telemetry) { 0L }

        session.open(isLive = false)
        session.onPlaying()
        session.open(isLive = true)

        assertEquals(
            UsageEvent.PlaybackSessionEnded(
                contentKind = ContentKind.VOD,
                endReason = PlaybackEndReason.REPLACED,
                activeDurationSeconds = 0L,
                completionBucket = CompletionBucket.UNKNOWN,
            ),
            telemetry.events.last(),
        )
    }

    private class RecordingTelemetry : QualityTelemetry {
        val events = mutableListOf<UsageEvent>()

        override fun applyCollection(
            crashTrackingEnabled: Boolean,
            usageStatisticsEnabled: Boolean,
        ) = Unit

        override fun log(event: UsageEvent) {
            events += event
        }

        override fun recordNonFatal(report: NonFatalReport) = Unit
    }
}
