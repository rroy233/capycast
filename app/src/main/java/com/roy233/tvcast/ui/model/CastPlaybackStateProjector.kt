package com.roy233.tvcast.ui.model

import com.roy233.tvcast.core.playback.PlaybackRequest
import com.roy233.tvcast.feature.playback.presentation.LiveQualityMapper
import com.roy233.tvcast.feature.playback.presentation.TrackOptionMapper
import com.roy233.tvcast.platform.player.VlcTrackFormat

/** 投屏会话命令到电视界面状态的唯一映射，保留未涉及的界面字段。 */
object CastPlaybackStateProjector {
    fun recentLoading(state: TvUiState, title: String): TvUiState = state.copy(
        mode = TvMode.LOADING,
        castTitle = title,
        destination = TvDestination.HOME,
        sidebarVisible = false,
        helpVisible = false,
        hudVisible = false,
    )

    fun incomingCast(state: TvUiState, title: String): TvUiState = state.copy(
        mode = TvMode.LOADING,
        showContinue = state.rememberPlaybackContent,
        castTitle = title.ifBlank { "正在投屏" },
        qualityLabel = "",
        qualityDetail = "",
        qualitySource = "",
        audioLabel = "",
        aid = 0L,
        cid = 0L,
        isPlaying = false,
        isBuffering = false,
        isLive = false,
        liveFormat = "",
        liveCdn = "",
        liveBackupCount = 0,
        positionMs = 0,
        durationMs = 0,
        hudVisible = true,
        panel = null,
        stopCastConfirm = false,
        error = null,
        tracks = emptyList(),
        audios = emptyList(),
    )

    fun resolving(state: TvUiState, title: String): TvUiState = state.copy(
        mode = TvMode.LOADING,
        showContinue = state.rememberPlaybackContent,
        castTitle = title.ifBlank { "正在投屏" },
        error = null,
        hudVisible = true,
    )

    fun playing(
        state: TvUiState,
        request: PlaybackRequest,
        seekToMs: Long,
        liveQualities: List<LiveQualityOption>,
    ): TvUiState = state.copy(
        mode = TvMode.PLAYING,
        showContinue = state.rememberPlaybackContent,
        castTitle = request.title,
        qualityLabel = if (request.isLive) LiveQualityMapper.label(request.video.id) else request.qualityLabel,
        qualityDetail = if (request.isLive) {
            request.video.codecs.ifBlank { VlcTrackFormat.codecName(request.video.codecId) }
        } else request.video.codecs,
        qualitySource = if (request.isLive) "直播" else if (request.video.id >= 80) "已提升" else "",
        audioLabel = if (request.isLive) "自动音轨" else request.audioLabel,
        aid = request.aid,
        cid = request.cid,
        isPlaying = true,
        isBuffering = false,
        isLive = request.isLive,
        liveFormat = request.liveFormat,
        liveCdn = request.liveCdn,
        liveBackupCount = request.liveBackupCount,
        liveQualities = liveQualities,
        positionMs = if (request.isLive) 0L else seekToMs.coerceAtLeast(0L),
        durationMs = if (request.isLive) 0L else request.durationSec * 1000,
        selectedQn = request.video.id,
        selectedAudioId = request.audio?.id ?: 0,
        tracks = TrackOptionMapper.tracks(request),
        audios = TrackOptionMapper.audios(request),
        hudVisible = true,
        panel = null,
        error = null,
        stopCastConfirm = false,
    )
}
