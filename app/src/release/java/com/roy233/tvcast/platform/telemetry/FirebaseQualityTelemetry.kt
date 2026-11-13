package com.roy233.tvcast.platform.telemetry

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.CustomKeysAndValues
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.roy233.tvcast.core.diagnostics.NonFatalReport
import com.roy233.tvcast.core.diagnostics.QualityTelemetry
import com.roy233.tvcast.core.diagnostics.UsageEvent

/** 将受限的质量遥测契约映射到 Firebase SDK。 */
internal class FirebaseQualityTelemetry(context: Context) : QualityTelemetry {
    private val analytics = FirebaseAnalytics.getInstance(context.applicationContext)
    private val crashlytics = FirebaseCrashlytics.getInstance()

    override fun applyCollection(crashTrackingEnabled: Boolean, usageStatisticsEnabled: Boolean) {
        crashlytics.setCrashlyticsCollectionEnabled(crashTrackingEnabled)
        analytics.setAnalyticsCollectionEnabled(usageStatisticsEnabled)
    }

    override fun log(event: UsageEvent) {
        val (name, parameters) = event.toFirebaseEvent()
        analytics.logEvent(name, parameters)
    }

    override fun recordNonFatal(report: NonFatalReport) {
        val keys =
            CustomKeysAndValues.Builder()
                .putString("component", report.component.name.lowercase())
                .putString("operation", report.operation.name.lowercase())
                .putString("backend", "media3")
                .apply {
                    report.contentKind?.let { putString("content_kind", it.name.lowercase()) }
                }
                .build()
        crashlytics.recordException(report.throwable, keys)
    }

    private fun UsageEvent.toFirebaseEvent(): Pair<String, Bundle> =
        when (this) {
            is UsageEvent.ScreenViewed ->
                FirebaseAnalytics.Event.SCREEN_VIEW to
                    Bundle().apply { putString("screen_name", screen.name.lowercase()) }
            is UsageEvent.CastResolved ->
                "cast_resolved" to
                    Bundle().apply {
                        putString("outcome", outcome.name.lowercase())
                        putString("content_kind", contentKind.name.lowercase())
                    }
            is UsageEvent.PlaybackStarted ->
                "playback_started" to
                    Bundle().apply { putString("content_kind", contentKind.name.lowercase()) }
            is UsageEvent.PlaybackSessionEnded ->
                "playback_session_end" to
                    Bundle().apply {
                        putString("content_kind", contentKind.name.lowercase())
                        putString("end_reason", endReason.name.lowercase())
                        putLong("active_duration_sec", activeDurationSeconds)
                        putString("completion_bucket", completionBucket.name.lowercase())
                    }
            is UsageEvent.FeatureUsed ->
                "feature_used" to Bundle().apply { putString("feature", feature.name.lowercase()) }
            is UsageEvent.UpdateChecked ->
                "update_checked" to
                    Bundle().apply { putString("outcome", outcome.name.lowercase()) }
        }
}
