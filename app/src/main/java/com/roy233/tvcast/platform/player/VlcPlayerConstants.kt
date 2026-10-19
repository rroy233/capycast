package com.roy233.tvcast.platform.player

/** 播放器诊断日志标签。 */
internal const val VLC_PLAYER_TAG = "VlcPlayer"

/** 周期播放监控间隔，单位为毫秒。 */
internal const val VLC_MONITOR_INTERVAL_MS = 1_000L

/** 播放统计采样间隔，单位为毫秒。 */
internal const val VLC_STATS_INTERVAL_MS = 5_000L

/** 播放位置时间线日志间隔，单位为毫秒。 */
internal const val VLC_TIME_LOG_INTERVAL_MS = 30_000L

/** 同类异常提示的最小间隔，单位为毫秒。 */
internal const val VLC_ANOMALY_INTERVAL_MS = 15_000L

/** 未收到 Opening 事件的告警阈值，单位为毫秒。 */
internal const val VLC_NO_OPENING_MS = 6_000L

/** 未收到 Playing 事件的告警阈值，单位为毫秒。 */
internal const val VLC_NO_PLAYING_MS = 20_000L

/** 未收到视频输出事件的告警阈值，单位为毫秒。 */
internal const val VLC_NO_VOUT_MS = 15_000L

/** 播放进度停滞的告警阈值，单位为毫秒。 */
internal const val VLC_STALL_MS = 12_000L

/** 原生日志为空的检查阈值，单位为毫秒。 */
internal const val VLC_NO_NATIVE_LOG_MS = 15_000L

/** 切换媒体时忽略旧媒体 Stopped 事件的保护窗口，单位为毫秒。 */
internal const val VLC_MEDIA_SWITCH_GRACE_MS = 1_500L

/** 单次轨道快照最多读取的媒体轨道数。 */
internal const val VLC_MAX_TRACKS = 16
