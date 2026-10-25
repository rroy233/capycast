package com.roy233.tvcast.ui.model

import android.graphics.Bitmap
import com.roy233.tvcast.core.danmaku.AiShieldLevel
import com.roy233.tvcast.feature.update.UpdateUiState

/**
 * 保存电视端页面、登录、投屏和播放控制所需的不可变状态。
 *
 * @property mode 当前显示模式。
 * @property destination 当前页面。
 * @property settingsPage 设置页面当前打开的层级。
 * @property sidebarVisible 侧边栏是否可见。
 * @property loggedIn 是否已登录。
 * @property accountName 当前账号名称。
 * @property mid 当前账号标识。
 * @property vip 当前账号是否为大会员。
 * @property deviceName 投屏设备名称。
 * @property roomName 设备所在房间名称。
 * @property network 当前网络类型。
 * @property ip 当前设备地址。
 * @property castTitle 当前投屏标题。
 * @property qualityLabel 当前画质名称。
 * @property qualityDetail 当前画质详情。
 * @property qualitySource 当前画质来源。
 * @property audioLabel 当前音轨名称。
 * @property aid 当前稿件标识。
 * @property cid 当前分P标识。
 * @property isPlaying 当前是否正在播放。
 * @property isBuffering 当前是否处于缓冲状态。
 * @property positionMs 当前播放位置，单位为毫秒。
 * @property durationMs 当前媒体时长，单位为毫秒。
 * @property seekPreviewPositionMs 遥控器进度调整期间预览的目标位置；为空表示没有待确认的跳转。
 * @property playbackSpeed 当前视频播放速度。
 * @property volume 当前音量值。
 * @property hudVisible 播放控制栏是否可见。
 * @property panel 当前打开的控制面板。
 * @property stopCastConfirm 是否显示停止投屏确认。
 * @property error 当前待展示的错误信息。
 * @property loginQr 登录二维码位图。
 * @property loginStatus 登录流程状态文本。
 * @property loginSkipped 是否跳过登录。
 * @property helpVisible 是否显示投屏帮助。
 * @property tracks 可选的视频清晰度列表。
 * @property audios 可选的音轨列表。
 * @property selectedQn 当前选中的清晰度标识。
 * @property selectedAudioId 当前选中的音轨标识。
 * @property toast 当前待展示的提示文本。
 * @property dlnaUrl DLNA 描述地址。
 * @property version 应用版本文本。
 * @property castActive 当前是否存在活动投屏。
 * @property castMode 当前投屏通道说明。
 * @property recents 最近投屏记录。
 * @property rememberPlaybackContent 是否记忆最近投屏内容。
 * @property syncPlaybackHistory 是否同步投屏播放记录到 B 站账号。
 * @property showContinue 是否展示继续投屏入口。
 * @property update 应用更新页面的状态。
 */
data class TvUiState(
    val mode: TvMode = TvMode.IDLE,
    val destination: TvDestination = TvDestination.HOME,
    val settingsPage: SettingsPage = SettingsPage.ROOT,
    val sidebarVisible: Boolean = true,
    val loggedIn: Boolean = false,
    val accountName: String = "未登录",
    val mid: Long = 0L,
    val vip: Boolean = false,
    val deviceName: String = "CapyCast",
    val roomName: String = "客厅",
    val network: String = "Wi-Fi",
    val ip: String = "",
    val castTitle: String = "",
    val qualityLabel: String = "",
    val qualityDetail: String = "",
    val qualitySource: String = "",
    val audioLabel: String = "",
    val aid: Long = 0L,
    val cid: Long = 0L,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    /** 当前内容是否为直播。 */
    val isLive: Boolean = false,
    /** 当前直播线路容器，如 fmp4 或 flv。 */
    val liveFormat: String = "",
    /** 当前直播线路 CDN 主机标签。 */
    val liveCdn: String = "",
    /** 当前线路之外的备用直播源数量。 */
    val liveBackupCount: Int = 0,
    /** 直播可手动切换的画质档位列表。 */
    val liveQualities: List<LiveQualityOption> = emptyList(),
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val seekPreviewPositionMs: Long? = null,
    val playbackSpeed: Float = 1f,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val volume: Int = 50,
    val hudVisible: Boolean = false,
    val panel: PanelKind? = null,
    val danmakuEnabled: Boolean = true,
    val danmakuAiShield: AiShieldLevel = AiShieldLevel.OFF,
    val danmakuAreaRatio: Float = 0.25f,
    val danmakuMaxOnScreen: Int = 60,
    val danmakuOpacity: Float = 1f,
    val danmakuFontScale: Float = 1f,
    val danmakuDurationSec: Float = 8f,
    val stopCastConfirm: Boolean = false,
    val error: TvError? = null,
    val loginQr: Bitmap? = null,
    val loginStatus: String = "正在获取二维码…",
    val loginSkipped: Boolean = false,
    val helpVisible: Boolean = false,
    val tracks: List<TrackOption> = emptyList(),
    val audios: List<AudioOption> = emptyList(),
    val selectedQn: Int = 0,
    val selectedAudioId: Int = 0,
    val toast: String? = null,
    val dlnaUrl: String = "",
    val version: String = "",
    val castActive: Boolean = false,
    val castMode: String = "DLNA 兼容",
    val recents: List<RecentCast> = emptyList(),
    val rememberPlaybackContent: Boolean = true,
    val syncPlaybackHistory: Boolean = true,
    val showContinue: Boolean = true,
    val update: UpdateUiState = UpdateUiState(),
)
