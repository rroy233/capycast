package com.roy233.tvcast.feature.playback.application

import com.roy233.tvcast.core.playback.PlaybackBackend
import com.roy233.tvcast.core.playback.PlaybackEvent

/** 接收已脱离播放器 SDK 的播放事件。 */
fun interface PlaybackEventConsumer {
    fun onPlaybackEvent(backend: PlaybackBackend, event: PlaybackEvent)
}

/** 将后端事件统一交给播放业务事件处理器。 */
class PlaybackEventRouter(
    private val consumer: PlaybackEventConsumer,
) {
    fun dispatch(backend: PlaybackBackend, event: PlaybackEvent) {
        consumer.onPlaybackEvent(backend, event)
    }
}
