package com.roy233.tvcast.ui.model

import android.graphics.Bitmap
import com.roy233.tvcast.core.danmaku.AiShieldLevel
import com.roy233.tvcast.feature.update.UpdateChannel
import com.roy233.tvcast.feature.update.UpdateUiState

/** 表示电视端播放器当前所处的显示与播放模式。 */
enum class TvMode { IDLE, LOADING, PLAYING, ERROR }

/** 表示电视端当前选中的业务页面。 */
enum class TvDestination { HOME, SETTINGS }

/** 表示设置页当前打开的层级。 */
enum class SettingsPage { ROOT, UPDATE, UPDATE_NOTES }

/** 表示播放控制面板的层级。 */
enum class PanelKind { ROOT, QUALITY, AUDIO, DANMAKU }

/**
 * 保存最近一次投屏内容的摘要信息。
 *
 * @property aid 视频的稿件标识。
 * @property cid 视频的分P标识。
 * @property title 视频标题。
 * @property cover 视频封面地址。
 * @property quality 最近一次使用的画质说明。
 * @property at 记录生成时间戳。
 */
data class RecentCast(
    val aid: Long,
    val cid: Long,
    val title: String,
    val cover: String = "",
    val quality: String = "",
    val at: Long = 0L,
)

/**
 * 描述可展示给用户的错误信息及其恢复能力。
 *
 * @property title 错误标题。
 * @property message 错误详情。
 * @property code 可选的错误代码。
 * @property canRetry 是否允许用户执行重试。
 */
data class TvError(
    val title: String,
    val message: String,
    val code: String = "",
    val canRetry: Boolean = true,
)

/**
 * 描述一个可选的视频清晰度。
 *
 * @property qn 清晰度数值标识。
 * @property label 面向用户展示的名称。
 * @property detail 清晰度补充说明。
 * @property codecid 视频编码标识。
 */
data class TrackOption(
    val qn: Int,
    val label: String,
    val detail: String,
    val codecid: Int,
)

/**
 * 描述一个可选的音轨。
 *
 * @property id 音轨标识。
 * @property label 面向用户展示的名称。
 */
data class AudioOption(
    val id: Int,
    val label: String,
)

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
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val seekPreviewPositionMs: Long? = null,
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
    val showContinue: Boolean = true,
    val update: UpdateUiState = UpdateUiState(),
)

/**
 * 聚合电视界面向宿主发送的用户操作回调。
 *
 * @property onLogin 请求开始登录。
 * @property onSkipLogin 跳过登录流程。
 * @property onOpenSidebar 打开侧边栏。
 * @property onCloseSidebar 关闭侧边栏。
 * @property onNavigateHome 导航到首页。
 * @property onNavigateSettings 导航到设置页。
 * @property onOpenUpdateSettings 打开应用更新二级页面。
 * @property onCloseUpdateSettings 返回设置主页。
 * @property onOpenUpdateNotes 打开当前更新的更新日志。
 * @property onCloseUpdateNotes 从更新日志返回应用更新页面。
 * @property onSelectUpdateChannel 选择更新通道。
 * @property onSetAutoUpdateCheck 设置是否自动检查更新。
 * @property onCheckForUpdates 手动检查更新。
 * @property onDownloadUpdate 下载当前可用更新。
 * @property onCancelUpdateDownload 取消当前更新下载。
 * @property onInstallUpdate 安装已经校验的更新包。
 * @property onShowHelp 显示投屏帮助。
 * @property onCloseHelp 关闭投屏帮助。
 * @property onLogout 请求退出登录。
 * @property onPlayPause 切换播放与暂停。
 * @property onSeekBy 按给定毫秒数调整播放位置。
 * @property onPreviewSeekBy 预览性地按给定毫秒数调整播放位置。
 * @property onConfirmSeek 确认当前预览位置并结束进度调整。
 * @property onSetVolume 设置音量。
 * @property onShowHud 显示播放控制栏。
 * @property onHideHud 隐藏播放控制栏。
 * @property onOpenPanel 打开指定控制面板。
 * @property onClosePanel 关闭控制面板。
 * @property onSelectQuality 选择视频清晰度。
 * @property onSelectAudio 选择音轨。
 * @property onRetry 重试当前失败操作。
 * @property onDismissError 关闭错误提示。
 * @property onRequestStopCast 请求停止当前投屏。
 * @property onConfirmStopCast 确认停止当前投屏。
 * @property onCancelStopCast 取消停止投屏操作。
 * @property onClearToast 清除提示文本。
 * @property onRecast 重新投屏指定记录。
 * @property onDismissContinue 收起继续播放入口。
 * @property onSetRememberPlaybackContent 设置是否记忆最近投屏内容。
 * @property onHomeBack 处理首页返回操作。
 */
class TvActions(
    val onLogin: () -> Unit = {},
    val onSkipLogin: () -> Unit = {},
    val onOpenSidebar: () -> Unit = {},
    val onCloseSidebar: () -> Unit = {},
    val onNavigateHome: () -> Unit = {},
    val onNavigateSettings: () -> Unit = {},
    val onOpenUpdateSettings: () -> Unit = {},
    val onCloseUpdateSettings: () -> Unit = {},
    val onOpenUpdateNotes: () -> Unit = {},
    val onCloseUpdateNotes: () -> Unit = {},
    val onSelectUpdateChannel: (UpdateChannel) -> Unit = {},
    val onSetAutoUpdateCheck: (Boolean) -> Unit = {},
    val onCheckForUpdates: () -> Unit = {},
    val onDownloadUpdate: () -> Unit = {},
    val onCancelUpdateDownload: () -> Unit = {},
    val onInstallUpdate: () -> Unit = {},
    val onShowHelp: () -> Unit = {},
    val onCloseHelp: () -> Unit = {},
    val onLogout: () -> Unit = {},
    val onPlayPause: () -> Unit = {},
    val onSeekBy: (Long) -> Unit = {},
    val onPreviewSeekBy: (Long) -> Unit = {},
    val onConfirmSeek: () -> Unit = {},
    val onToggleDanmaku: () -> Unit = {},
    val onSetDanmakuAiShield: (AiShieldLevel) -> Unit = {},
    val onSetDanmakuArea: (Float) -> Unit = {},
    val onSetDanmakuMaxOnScreen: (Int) -> Unit = {},
    val onSetDanmakuOpacity: (Float) -> Unit = {},
    val onSetDanmakuFontScale: (Float) -> Unit = {},
    val onSetDanmakuDuration: (Float) -> Unit = {},
    val onSetVolume: (Int) -> Unit = {},
    val onShowHud: () -> Unit = {},
    val onHideHud: () -> Unit = {},
    val onOpenPanel: (PanelKind) -> Unit = {},
    val onClosePanel: () -> Unit = {},
    val onSelectQuality: (Int) -> Unit = {},
    val onSelectAudio: (Int) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onDismissError: () -> Unit = {},
    val onRequestStopCast: () -> Unit = {},
    val onConfirmStopCast: () -> Unit = {},
    val onCancelStopCast: () -> Unit = {},
    val onClearToast: () -> Unit = {},
    val onRecast: (RecentCast) -> Unit = {},
    val onDismissContinue: () -> Unit = {},
    val onSetRememberPlaybackContent: (Boolean) -> Unit = {},
    val onHomeBack: () -> Unit = {},
)
