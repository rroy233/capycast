package com.roy233.tvcast.core.diagnostics

/** 匿名使用统计的受限事件集合；事件模型不允许携带媒体或账号身份。 */
sealed interface UsageEvent {
    data class ScreenViewed(val screen: Screen) : UsageEvent

    data class CastResolved(val outcome: Outcome, val contentKind: ContentKind) : UsageEvent

    data class PlaybackStarted(val contentKind: ContentKind) : UsageEvent

    data class PlaybackSessionEnded(
        val contentKind: ContentKind,
        val endReason: PlaybackEndReason,
        val activeDurationSeconds: Long,
        val completionBucket: CompletionBucket,
    ) : UsageEvent

    data class FeatureUsed(val feature: Feature) : UsageEvent

    data class UpdateChecked(val outcome: Outcome) : UsageEvent
}

enum class Screen {
    HOME,
    SETTINGS,
    UPDATE,
    UPDATE_NOTES,
}

enum class Outcome {
    SUCCESS,
    FAILURE,
}

enum class ContentKind {
    VOD,
    LIVE,
}

enum class PlaybackEndReason {
    COMPLETED,
    USER_STOPPED,
    ERROR,
    REPLACED,
    ACTIVITY_DESTROYED,
}

enum class CompletionBucket {
    UNKNOWN,
    UNDER_25,
    FROM_25_TO_74,
    FROM_75_TO_99,
    COMPLETE,
}

enum class Feature {
    PLAY_PAUSE,
    QUALITY,
    AUDIO,
    SUBTITLE,
    DANMAKU,
    PLAYBACK_SETTING,
}

/** 可用于崩溃报告的固定诊断上下文。 */
data class NonFatalReport(
    val throwable: Throwable,
    val component: Component,
    val operation: Operation,
    val contentKind: ContentKind? = null,
) {
    enum class Component {
        STARTUP,
        CAST,
        PLAYBACK_SELECTION,
    }

    enum class Operation {
        PLAYER_ENGINE_INITIALIZATION,
        SESSION_INTERNAL,
        SELECTION_INTERNAL,
    }
}

/** 质量遥测的稳定端口；Firebase 仅在平台实现中出现。 */
interface QualityTelemetry {
    fun applyCollection(crashTrackingEnabled: Boolean, usageStatisticsEnabled: Boolean)

    fun log(event: UsageEvent)

    fun recordNonFatal(report: NonFatalReport)
}

object NoOpQualityTelemetry : QualityTelemetry {
    override fun applyCollection(crashTrackingEnabled: Boolean, usageStatisticsEnabled: Boolean) =
        Unit

    override fun log(event: UsageEvent) = Unit

    override fun recordNonFatal(report: NonFatalReport) = Unit
}
