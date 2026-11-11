package com.roy233.tvcast.ui.model

import android.graphics.Bitmap
import com.roy233.tvcast.feature.cast.domain.RecentCast
import com.roy233.tvcast.feature.danmaku.domain.AiShieldLevel
import com.roy233.tvcast.feature.display.CalibrationPhase
import com.roy233.tvcast.feature.display.CalibrationState
import com.roy233.tvcast.feature.update.UpdateUiState

/** 最近投屏卡片的精确焦点身份；同一稿件的不同分 P 也必须区分。 */
data class RecentCastFocus(val aid: Long, val cid: Long)

/**
 * 保存电视端页面、登录、投屏和播放控制所需的不可变状态。
 *
 * @property mode 当前显示模式。
 * @property destination 当前页面。
 * @property settingsNavigation 设置页的路由栈与焦点恢复请求。
 * @property sidebarVisible 左侧导航菜单是否可见。
 * @property focusRequest 栈顶焦点请求；路由栈每次变化都写入新的栈顶锚点。
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
 * @property loginDialogVisible 是否显示扫码登录弹窗。
 * @property loginSkipped 是否跳过登录。
 * @property helpVisible 是否显示投屏帮助。
 * @property tracks 可选的视频清晰度列表。
 * @property audios 可选的音轨列表。
 * @property selectedQn 当前选中的清晰度标识。
 * @property selectedAudioId 当前选中的音轨标识。
 * @property toast 当前待展示的提示文本。
 * @property dlnaUrl DLNA 描述地址。
 * @property version 应用版本文本。
 * @property versionCode 应用版本号。
 * @property castActive 当前是否存在活动投屏。
 * @property castMode 当前投屏通道说明。
 * @property recents 最近投屏记录。
 * @property focusedRecent 首页最近投屏中被聚焦的记录身份，用于切换大标题与背景。
 * @property rememberPlaybackContent 是否记忆最近投屏内容。
 * @property syncPlaybackHistory 是否同步投屏播放记录到 B 站账号。
 * @property showContinue 是否展示继续投屏入口。
 * @property update 应用更新页面的状态。
 */
data class TvUiState(
    val mode: TvMode = TvMode.IDLE,
    val destination: TvDestination = TvDestination.HOME,
    val settingsNavigation: SettingsNavigationState = SettingsNavigationState(),
    val sidebarVisible: Boolean = false,
    val focusRequest: FocusRequest = FocusRequest(UiFocusAnchor.HOME_PRIMARY),
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
    val loginStatus: String = "",
    val loginDialogVisible: Boolean = false,
    val loginSkipped: Boolean = false,
    val helpVisible: Boolean = false,
    val privacyNoticeVisible: Boolean = false,
    val privacyAgreementVisible: Boolean = false,
    /** 欢迎弹窗经协议链接打开协议模态层后，关闭时恢复欢迎弹窗。 */
    val returnToWelcomeAfterPrivacyAgreement: Boolean = false,
    val tracks: List<TrackOption> = emptyList(),
    val audios: List<AudioOption> = emptyList(),
    val selectedQn: Int = 0,
    val selectedAudioId: Int = 0,
    val toast: String? = null,
    val dlnaUrl: String = "",
    val version: String = "",
    val versionCode: Long = 0L,
    val castActive: Boolean = false,
    val castMode: String = "DLNA 兼容",
    val recents: List<RecentCast> = emptyList(),
    val focusedRecent: RecentCastFocus? = null,
    val rememberPlaybackContent: Boolean = true,
    val syncPlaybackHistory: Boolean = true,
    val crashTrackingEnabled: Boolean = true,
    val usageStatisticsEnabled: Boolean = true,
    val showContinue: Boolean = true,
    val update: UpdateUiState = UpdateUiState(),
    val calibration: CalibrationState = CalibrationState(),
) {
    /** 当前叠在目的页之上的浮层，按优先级从高到低排列。 */
    val overlayStack: List<UiOverlay>
        get() = buildList {
            if (privacyAgreementVisible) add(UiOverlay.PRIVACY_AGREEMENT)
            if (privacyNoticeVisible) add(UiOverlay.PRIVACY_NOTICE)
            if (calibration.phase != CalibrationPhase.HIDDEN) add(UiOverlay.CALIBRATION)
            if (stopCastConfirm) add(UiOverlay.STOP_CAST_CONFIRM)
            if (error != null) add(UiOverlay.ERROR)
            if (panel != null) add(UiOverlay.PANEL)
            if (helpVisible) add(UiOverlay.HELP)
            if (loginDialogVisible) add(UiOverlay.LOGIN_DIALOG)
        }

    /** 当前拥有按键与焦点的浮层；为 null 表示栈顶是目的页。 */
    val topOverlay: UiOverlay?
        get() = overlayStack.firstOrNull()

    /**
     * 首页当前展示的最近投屏。
     *
     * 焦点所在的记录优先，其次是最新一条；收起继续投屏时为空。背景、大标题与主按钮 都由它派生，因此“焦点换一个卡片，整页跟着换”只需维护一个字段。
     */
    val featuredRecent: RecentCast?
        get() =
            when {
                !showContinue -> null
                focusedRecent != null ->
                    recents.firstOrNull {
                        it.aid == focusedRecent.aid && it.cid == focusedRecent.cid
                    } ?: recents.firstOrNull()
                else -> recents.firstOrNull()
            }

    /** 当前设置页面；仅为渲染与既有投影提供只读视图。 */
    val settingsPage: SettingsPage
        get() = settingsNavigation.currentPage

    /**
     * 打开左侧菜单：菜单成为栈顶，焦点落在当前目的页对应的条目上。
     *
     * 菜单可见与栈顶锚点必须同时成立，因此打开动作只能经由这里， 调用点不再单独写 `sidebarVisible = true`。
     */
    fun withMenuOpen(): TvUiState =
        copy(
            sidebarVisible = true,
            focusRequest = focusRequest.moveTo(menuEntryFor(destination).anchor),
        )

    /** 收起左侧菜单：焦点交还给当前页面的顶层锚点。 */
    fun withMenuClosed(): TvUiState = copy(sidebarVisible = false).focusCurrentPage()

    /**
     * 结束投屏后返回首页，并把焦点交给首页主操作。
     *
     * 用户停止、DLNA Stop、自然结束与错误退出都使用这一条 UI 转换，避免某条终止路径 只切换页面却遗留已销毁播放器的焦点。最近投屏的展示仍由最新记录派生，但不保留
     * 已离开卡片的“当前焦点”标识。
     */
    fun returnToHomeAfterPlayback(): TvUiState =
        copy(
                destination = TvDestination.HOME,
                settingsNavigation = settingsNavigation.reset(),
                sidebarVisible = false,
                focusedRecent = null,
            )
            .focusCurrentPage()

    /**
     * 把栈顶焦点指向当前页面的顶层锚点。
     *
     * 设置页的顶层由它的路由栈决定（可能停在某个子页），其余目的页用自身锚点， 因此“栈顶 = 当前页面”这一条规则在模型里只写这一次。
     */
    fun focusCurrentPage(): TvUiState =
        copy(
            // 首页顶层锚点是主操作；卡片焦点不能跨菜单、浮层或投屏会话保留。
            focusedRecent = if (destination == TvDestination.HOME) null else focusedRecent,
            focusRequest =
                focusRequest.moveTo(
                    if (destination == TvDestination.SETTINGS) {
                        settingsNavigation.topAnchor
                    } else {
                        pageAnchorFor(destination)
                    },
                ),
        )
}
