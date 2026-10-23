package com.roy233.tvcast

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.roy233.tvcast.ui.model.PanelKind
import com.roy233.tvcast.ui.model.PlaybackSpeed
import com.roy233.tvcast.ui.model.RecentCast
import com.roy233.tvcast.ui.model.SettingsPage
import com.roy233.tvcast.ui.model.TvActions
import com.roy233.tvcast.ui.model.TvDestination
import com.roy233.tvcast.ui.model.TvError
import com.roy233.tvcast.ui.model.TvMode
import com.roy233.tvcast.ui.model.TvUiState
import com.roy233.tvcast.ui.tv.TvRoot
import com.roy233.tvcast.ui.tv.DoubleBackExitGate
import com.roy233.tvcast.ui.tv.HomeBackExitTiming
import com.roy233.tvcast.ui.tv.TvKeyHandler
import com.roy233.tvcast.data.local.RecentCastStore
import com.roy233.tvcast.data.local.UiPreferences
import com.roy233.tvcast.data.auth.AuthTokenStore
import com.roy233.tvcast.data.bili.BiliApi
import com.roy233.tvcast.data.bili.LiveCdnCoordinator
import com.roy233.tvcast.data.bili.LivePlayInfo
import com.roy233.tvcast.data.bili.LivePlaybackHealth
import com.roy233.tvcast.data.bili.LivePlaybackStallWatchdog
import com.roy233.tvcast.data.bili.LivePlayUrlResolver
import com.roy233.tvcast.data.bili.LiveStreamCandidate
import com.roy233.tvcast.data.bili.RankedLiveSource
import com.roy233.tvcast.ui.model.LiveQualityOption
import com.roy233.tvcast.data.bili.NvaExtDecoder
import com.roy233.tvcast.core.diagnostics.DeviceDiagnostics
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction
import com.roy233.tvcast.core.diagnostics.PlaybackDiagnostics
import com.roy233.tvcast.core.danmaku.AiShieldLevel
import com.roy233.tvcast.core.danmaku.DanmakuController
import com.roy233.tvcast.core.danmaku.DanmakuSettings
import com.roy233.tvcast.core.danmaku.DfmDanmakuEngine
import com.roy233.tvcast.core.danmaku.MediaClock
import com.roy233.tvcast.data.local.DanmakuPreferences
import com.roy233.tvcast.feature.account.AccountLoginCoordinator
import com.roy233.tvcast.feature.update.UpdateCoordinator
import com.roy233.tvcast.platform.device.DeviceInfoProvider
import com.roy233.tvcast.platform.dlna.DlnaRenderer
import com.roy233.tvcast.platform.qr.QrCodeGenerator
import com.roy233.tvcast.feature.cast.domain.TrackOptionFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import com.roy233.tvcast.platform.player.VlcPlayerEngine
import com.roy233.tvcast.platform.player.DolbyVisionPlayerEngine
import com.roy233.tvcast.platform.player.VlcTrackFormat
import master.flame.danmaku.ui.widget.DanmakuView

/**
 * 应用主界面及投屏生命周期控制器。
 *
 * 负责连接 VLC 播放器、DLNA 渲染器、账号登录协调器和 Compose 电视界面。
 */
@UnstableApi
class MainActivity : ComponentActivity() {

    /** VLC 播放引擎。 */
    private lateinit var playerEngine: VlcPlayerEngine
    /** VLC 播放器实例。 */
    private val player: MediaPlayer get() = playerEngine.player
    /** 当前活跃的播放后端；HDR/SDR 使用 VLC，杜比视界使用 Media3。 */
    private var playbackBackend = PlaybackBackend.VLC
    /** 杜比视界播放器按需创建，避免普通播放增加额外资源占用。 */
    private var dolbyVisionPlayer: DolbyVisionPlayerEngine? = null
    /** 视频渲染布局。 */
    private lateinit var videoLayout: VLCVideoLayout
    /** Media3 使用的 SurfaceView 容器，仅杜比视界会显示。 */
    private lateinit var dolbyVisionPlayerView: PlayerView
    /** 弹幕渲染视图。 */
    private lateinit var danmakuView: DanmakuView
    /** 弹幕会话控制器。 */
    private lateinit var danmakuController: DanmakuController
    /** 弹幕媒体时钟。 */
    private val danmakuClock = MediaClock()
    /** 当前弹幕设置。 */
    private var danmakuSettings = DanmakuSettings()
    /** 最近一次已知的视频宽高。 */
    private var videoWidth = 0
    private var videoHeight = 0
    /** Compose 界面承载视图。 */
    private lateinit var composeUi: ComposeView
    /** DLNA 投屏渲染器。 */
    private lateinit var dlna: DlnaRenderer

    /** 主线程协程作用域。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** 当前媒体解析任务。 */
    private var resolveJob: Job? = null
    /** 直播备用线路刷新任务。 */
    private var liveBackupRefreshJob: Job? = null
    /** 播放进度轮询任务。 */
    private var tickerJob: Job? = null

    /** 当前播放信息。 */
    private var current: BiliApi.PlayInfo? = null
    /** 当前是否存在活动投屏。 */
    private var castActive = false
    /** 当前是否正在解析媒体信息。 */
    private var resolving = false
    /** 是否在播放器准备完成后保持暂停。 */
    private var pendingPause = false
    /** 待应用的播放起始位置，单位为毫秒。 */
    private var pendingSeekMs = 0L
    /** 最近一次解析得到的 nva 内容。 */
    private var lastNva: NvaExtDecoder.NvaExtContent? = null
    /** 最近一次投屏标题。 */
    private var lastTitle = ""
    /** 最近一次失败操作的重试回调。 */
    private var lastErrorRetry: (() -> Unit)? = null
    /** 最近一次播放器读取失败告警的单调时刻。 */
    private var lastPlayerReadWarningAt = 0L
    /** 当前直播线路与同画质故障切换状态。 */
    private var liveSession: LiveSession? = null
    /** 直播线路测速与排序器。 */
    private val liveCdnCoordinator = LiveCdnCoordinator()
    /** 直播缓冲阈值统计。 */
    private val livePlaybackHealth = LivePlaybackHealth()
    /** 直播时钟为零时改用读取和解码计数检测卡顿。 */
    private val liveStallWatchdog = LivePlaybackStallWatchdog()
    /** 避免多路播放器事件同时启动多个线路切换。 */
    private var liveSwitchInProgress = false
    /** 等待 VLC 确认开始播放后显示右下角切源提示的信息。 */
    private var pendingLiveRouteSwitch: PendingLiveRouteSwitch? = null
    private sealed interface CastResolution {
        data class Vod(val info: BiliApi.PlayInfo) : CastResolution
        data class Live(
            val info: LivePlayInfo,
            val routes: List<RankedLiveSource>,
        ) : CastResolution
    }

    private data class LiveSession(
        var stream: LivePlayInfo,
        var routes: MutableList<RankedLiveSource>,
        var active: LiveStreamCandidate,
        val attemptedUrls: MutableSet<String> = mutableSetOf(),
        val downshiftedQualities: MutableSet<Int> = mutableSetOf(),
        var avcFallbackAttempted: Boolean = false,
    )

    private data class PendingLiveRouteSwitch(
        val reason: String,
        val from: LiveStreamCandidate?,
        val target: LiveStreamCandidate,
        val firstByteMs: Long?,
        val throughputBytesPerSecond: Long?,
    )

    /** 电视界面状态。 */
    private var ui by mutableStateOf(TvUiState())
    /** 最近投屏记录存储。 */
    private val recentCastStore by lazy { RecentCastStore(this) }
    /** TV 登录令牌存储；直播协议需要其中的 access_key。 */
    private val authTokenStore by lazy { AuthTokenStore(this) }
    /** 首页双击返回退出计时。 */
    private val homeBackExitGate = DoubleBackExitGate(HOME_BACK_EXIT_WINDOW_MS)
    /** 设备身份偏好读取器。 */
    private val uiPreferences by lazy { UiPreferences(this) }
    /** 弹幕设置读取器。 */
    private val danmakuPreferences by lazy { DanmakuPreferences(this) }
    /** 设备信息读取器。 */
    private val deviceInfo by lazy { DeviceInfoProvider(this) }
    /** 对外显示并广播的投屏设备名称。 */
    private val castDeviceName by lazy {
        deviceInfo.castDeviceName(uiPreferences.deviceName)
    }
    /** 账号二维码登录协调器。 */
    private val loginCoordinator by lazy {
        AccountLoginCoordinator(
            context = this,
            scope = scope,
            readState = { ui },
            writeState = { ui = it },
            authTokenStore = authTokenStore,
            qrCodeGenerator = QrCodeGenerator(),
            onTokenAvailable = { BiliApi.cookie = it.cookie },
            onLogout = { BiliApi.cookie = "" },
        )
    }
    /** 应用更新流程协调器。 */
    private val updateCoordinator by lazy {
        UpdateCoordinator(
            context = this,
            scope = scope,
            readState = { ui },
            writeState = { ui = it },
        )
    }

    /** 提供给电视界面的操作回调集合。 */
    private val actions = TvActions(
        onLogin = { startQrLogin() },
        onSkipLogin = { skipLogin() },
        onOpenSidebar = {
            ui = ui.copy(sidebarVisible = true)
        },
        onCloseSidebar = {
            ui = ui.copy(sidebarVisible = false)
        },
        onNavigateHome = {
            ui = ui.copy(
                destination = TvDestination.HOME,
                settingsPage = SettingsPage.ROOT,
                sidebarVisible = true,
                hudVisible = ui.mode == TvMode.PLAYING,
            )
        },
        onNavigateSettings = {
            ui = ui.copy(
                destination = TvDestination.SETTINGS,
                settingsPage = SettingsPage.ROOT,
                sidebarVisible = true,
                hudVisible = false,
            )
        },
        onOpenUpdateSettings = { updateCoordinator.openSettings() },
        onCloseUpdateSettings = { updateCoordinator.closeSettings() },
        onOpenUpdateNotes = { updateCoordinator.openNotes() },
        onCloseUpdateNotes = { updateCoordinator.closeNotes() },
        onSelectUpdateChannel = { channel -> updateCoordinator.selectChannel(channel) },
        onSetAutoUpdateCheck = { enabled -> updateCoordinator.setAutomaticCheck(enabled) },
        onCheckForUpdates = { updateCoordinator.checkForUpdates() },
        onDownloadUpdate = { updateCoordinator.downloadUpdate() },
        onCancelUpdateDownload = { updateCoordinator.cancelUpdateDownload() },
        onInstallUpdate = { updateCoordinator.installUpdate() },
        onShowHelp = {
            ui = ui.copy(helpVisible = true)
        },
        onCloseHelp = { ui = ui.copy(helpVisible = false) },
        onLogout = { logout() },
        onPlayPause = { togglePlayPause() },
        onSeekBy = { seekBy(it) },
        onPreviewSeekBy = { previewSeekBy(it) },
        onConfirmSeek = { confirmPreviewSeek() },
        onSetPlaybackSpeed = { setPlaybackSpeed(it) },
        onToggleDanmaku = {
            if (ui.isLive) {
                ui = ui.copy(toast = "直播暂不支持弹幕")
            } else {
                val enabled = !danmakuSettings.enabled
                updateDanmakuSettings(danmakuSettings.copy(enabled = enabled))
                ui = ui.copy(toast = if (enabled) "弹幕已开启" else "弹幕已关闭")
            }
        },
        onSetDanmakuAiShield = {
            updateDanmakuSettings(danmakuSettings.copy(aiShield = it))
        },
        onSetDanmakuArea = {
            updateDanmakuSettings(danmakuSettings.copy(areaRatio = it))
        },
        onSetDanmakuMaxOnScreen = {
            updateDanmakuSettings(danmakuSettings.copy(maxOnScreen = it))
        },
        onSetDanmakuOpacity = {
            updateDanmakuSettings(danmakuSettings.copy(opacity = it))
        },
        onSetDanmakuFontScale = {
            updateDanmakuSettings(danmakuSettings.copy(fontScale = it))
        },
        onSetDanmakuDuration = {
            updateDanmakuSettings(danmakuSettings.copy(durationSec = it))
        },
        onSetVolume = { setVolume(it) },
        onShowHud = { ui = ui.copy(hudVisible = true) },
        onHideHud = {
            if (ui.mode == TvMode.PLAYING &&
                ui.panel == null &&
                ui.error == null &&
                !ui.stopCastConfirm
            ) {
                ui = ui.copy(hudVisible = false)
            }
        },
        onOpenPanel = { kind ->
            if (ui.isLive && kind != PanelKind.ROOT && kind != PanelKind.LIVE_QUALITY) {
                LogHub.i(TAG, "live playback ignored unsupported panel=$kind")
            } else {
                ui = ui.copy(
                    panel = kind,
                    hudVisible = true,
                    stopCastConfirm = false,
                )
            }
        },
        onClosePanel = { ui = ui.copy(panel = null, hudVisible = true) },
        onSelectQuality = { qn -> switchQuality(qn) },
        onSelectAudio = { id -> switchAudio(id) },
        onRetry = { retryLastAction() },
        onDismissError = { dismissError() },
        onRequestStopCast = {
            ui = ui.copy(stopCastConfirm = true, hudVisible = true)
        },
        onConfirmStopCast = { stopCasting() },
        onCancelStopCast = { ui = ui.copy(stopCastConfirm = false, hudVisible = true) },
        onClearToast = { clearToast() },
        onRecast = { recast(it) },
        onDismissContinue = { ui = ui.copy(showContinue = false) },
        onSetRememberPlaybackContent = { enabled ->
            uiPreferences.rememberPlaybackContent = enabled
            if (enabled) {
                ui = ui.copy(
                    rememberPlaybackContent = true,
                    showContinue = ui.recents.isNotEmpty(),
                )
            } else {
                recentCastStore.clear()
                ui = ui.copy(
                    rememberPlaybackContent = false,
                    recents = emptyList(),
                    showContinue = false,
                )
            }
        },
        onHomeBack = { handleHomeBack() },
    )

    /** 接收 DLNA 渲染器事件并转发给播放器与界面状态。 */
    private val dlnaListener = object : DlnaRenderer.Listener {
        /** 处理新的投屏媒体请求。 */
        override fun onCast(uri: String, metadata: String, title: String, longDescription: String) {
            LogHub.i(
                TAG,
                "dlna cast uri=${LogRedaction.url(uri)} title=${title.take(80)} " +
                    "metadata=${metadata.length}B longDescription=${longDescription.length}B",
            )
            runOnUiThread { handleCast(uri, title, longDescription) }
        }

        /** 处理远端播放命令。 */
        override fun onPlay() {
            pendingPause = false
            if (::playerEngine.isInitialized) {
                setPlayerPaused(false, "dlna-play")
            } else {
                LogHub.w(TAG, "dlna play ignored: player engine unavailable")
            }
            runOnUiThread { ui = ui.copy(isPlaying = true, hudVisible = true) }
        }

        /** 处理远端暂停命令。 */
        override fun onPause() {
            pendingPause = true
            if (::playerEngine.isInitialized) {
                setPlayerPaused(true, "dlna-pause")
            } else {
                LogHub.w(TAG, "dlna pause ignored: player engine unavailable")
            }
            runOnUiThread { ui = ui.copy(isPlaying = false, hudVisible = true) }
        }

        /** 处理远端停止命令。 */
        override fun onStop() {
            pendingPause = false
            clearLiveSession("dlna-stop")
            if (::playerEngine.isInitialized) {
                stopActivePlayback("dlna-stop")
            } else {
                LogHub.w(TAG, "dlna stop ignored: player engine unavailable")
            }
            castActive = false
            current = null
            LogHub.setCast("")
            LogHub.setPlay("")
            runOnUiThread {
                ui = ui.copy(
                    mode = TvMode.IDLE,
                    destination = TvDestination.HOME,
                    sidebarVisible = true,
                    castTitle = "",
                    qualityLabel = "",
                    qualityDetail = "",
                    qualitySource = "",
                    audioLabel = "",
                    isPlaying = false,
                    isBuffering = false,
                    isLive = false,
                    liveFormat = "",
                    liveCdn = "",
                    liveBackupCount = 0,
                    positionMs = 0,
                    durationMs = 0,
                    hudVisible = false,
                    tracks = emptyList(),
                    audios = emptyList(),
                    toast = "投屏已结束",
                    showContinue = ui.rememberPlaybackContent,
                )
            }
        }

        /** 处理远端跳转命令。 */
        override fun onSeek(positionMs: Long) {
            if (current?.isLive == true) {
                LogHub.w(TAG, "dlna seek rejected for live room=${current?.cid}")
                return
            }
            if (::playerEngine.isInitialized) {
                seekPlayer(positionMs, "dlna-seek")
            } else {
                LogHub.w(TAG, "dlna seek ignored: player engine unavailable")
            }
            runOnUiThread {
                ui = ui.copy(
                    positionMs = positionMs.coerceAtLeast(0L),
                    hudVisible = true,
                )
                if (::danmakuController.isInitialized) {
                    danmakuController.onSeek(positionMs.coerceAtLeast(0L))
                }
            }
        }

        /** 直播没有定位能力，其他媒体按 libVLC 能力报告定位状态。 */
        override fun isSeekable(): Boolean = current?.isLive != true &&
            runCatching { player.isSeekable }.getOrDefault(false)

        /** 处理远端音量设置命令。 */
        override fun onSetVolume(volume: Int) {
            val v = volume.coerceIn(0, 100)
            if (::playerEngine.isInitialized) {
                setPlayerVolume(v, "dlna-volume")
            } else {
                LogHub.w(TAG, "dlna volume ignored: player engine unavailable")
            }
            runOnUiThread { ui = ui.copy(volume = v, hudVisible = true) }
        }

        /** 返回播放器当前播放位置。 */
        override fun positionMs(): Long =
            if (current?.isLive == true) 0L else playerPositionMs()

        /** 返回播放器当前媒体时长。 */
        override fun durationMs(): Long =
            if (current?.isLive == true) 0L else playerDurationMs()

        /** 返回播放器当前是否正在播放。 */
        override fun isPlaying(): Boolean =
            playerIsPlaying()

        /** 返回播放器当前音量。 */
        override fun volume(): Int =
            playerVolume()
    }

    /** 初始化播放器、投屏服务、界面和账号状态。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LogHub.start(this)
        PlaybackDiagnostics.install(this)
        DeviceDiagnostics.collectAsync(this)
        installCrashHandler()
        LogHub.i(
            TAG,
            "app start version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) " +
                "device=${Build.MANUFACTURER}/${Build.MODEL} " +
                "android=${Build.VERSION.RELEASE}/${Build.VERSION.SDK_INT} " +
                "abis=${Build.SUPPORTED_ABIS.joinToString(",")}",
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                TvKeyHandler.dispatchBack(ui, actions)
            }
        })

        videoLayout = findViewById(R.id.videoLayout)
        dolbyVisionPlayerView = findViewById(R.id.dolbyVisionPlayerView)
        danmakuView = findViewById(R.id.danmakuView)
        danmakuSettings = danmakuPreferences.load()
        composeUi = findViewById(R.id.composeUi)
        composeUi.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        composeUi.setContent {
            TvRoot(state = ui, actions = actions)
        }
        composeUi.isFocusable = true
        composeUi.post { composeUi.requestFocus() }

        dlna = DlnaRenderer(this, dlnaListener, deviceName = castDeviceName)
        dlna.start()

        ui = ui.copy(
            deviceName = castDeviceName,
            roomName = uiPreferences.roomName,
            network = deviceInfo.wifiName(),
            ip = LogHub.localIp(),
            version = deviceInfo.versionName(),
            dlnaUrl = dlna.descriptionUrl,
            danmakuEnabled = danmakuSettings.enabled,
            danmakuAiShield = danmakuSettings.aiShield,
            danmakuAreaRatio = danmakuSettings.areaRatio,
            danmakuMaxOnScreen = danmakuSettings.maxOnScreen,
            danmakuOpacity = danmakuSettings.opacity,
            danmakuFontScale = danmakuSettings.fontScale,
            danmakuDurationSec = danmakuSettings.durationSec,
        )
        updateCoordinator.initialize()

        runCatching {
            VlcPlayerEngine(this, videoLayout) { event -> handlePlayerEvent(event) }
        }.onSuccess { engine ->
            playerEngine = engine
            val danmakuEngine = DfmDanmakuEngine(danmakuView, danmakuClock)
            danmakuController = DanmakuController(scope, danmakuEngine, danmakuClock)
            danmakuController.applySettings(danmakuSettings)
            danmakuView.visibility = if (danmakuSettings.enabled) View.VISIBLE else View.GONE
            videoLayout.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                layoutDanmaku()
            }
            videoLayout.post { layoutDanmaku() }
        }.onFailure { error ->
            LogHub.e(
                TAG,
                "player engine init failed: ${LogRedaction.reason(error)}",
                error,
                stack = true,
            )
            PlaybackDiagnostics.fail(
                code = "PLAYER_INIT_FAILED",
                message = "播放器初始化失败",
                detail = "reason=${LogRedaction.reason(error)} " +
                    "device=${Build.MANUFACTURER}/${Build.MODEL}",
            )
            showError(
                title = "播放器初始化失败",
                message = "设备可能缺少必要的解码组件，请查看诊断日志。",
                code = "PLAYER_INIT_FAILED",
                canRetry = false,
            )
        }

        val rememberPlaybackContent = uiPreferences.rememberPlaybackContent
        ui = ui.copy(
            rememberPlaybackContent = rememberPlaybackContent,
            recents = if (rememberPlaybackContent) recentCastStore.load() else emptyList(),
            showContinue = rememberPlaybackContent,
        )
        startOrLogin()
        startTicker()
        updateCoordinator.checkAutomaticallyOnLaunch()
    }

    /** 按需创建杜比视界播放后端。 */
    private fun dolbyVisionEngine(): DolbyVisionPlayerEngine =
        dolbyVisionPlayer ?: DolbyVisionPlayerEngine(
            context = this,
            playerView = dolbyVisionPlayerView,
            events = object : DolbyVisionPlayerEngine.Events {
                override fun onPlaying() = handleDolbyVisionPlaying()
                override fun onPaused() = handleDolbyVisionPaused()
                override fun onBuffering(buffering: Boolean) = handleDolbyVisionBuffering(buffering)
                override fun onEnded() = handleDolbyVisionEnded()
                override fun onError(error: PlaybackException) = handleDolbyVisionError(error)
                override fun onVideoSizeChanged(width: Int, height: Int) {
                    if (playbackBackend == PlaybackBackend.MEDIA3) {
                        videoWidth = width
                        videoHeight = height
                        ui = ui.copy(videoWidth = width, videoHeight = height)
                        layoutDanmaku()
                    }
                }
            },
        ).also { dolbyVisionPlayer = it }

    /** Media3 的状态回调映射为现有 UI 与弹幕状态机。 */
    private fun handleDolbyVisionPlaying() {
        if (playbackBackend != PlaybackBackend.MEDIA3) return
        val seek = pendingSeekMs
        pendingSeekMs = 0L
        ui = ui.copy(
            mode = TvMode.PLAYING,
            isPlaying = true,
            isBuffering = false,
            positionMs = if (seek > 0L) seek else playerPositionMs(),
            durationMs = playerDurationMs().takeIf { it > 0L } ?: ui.durationMs,
        )
        if (::danmakuController.isInitialized) {
            val position = if (seek > 0L) seek else playerPositionMs()
            danmakuController.onPlaying(position)
            if (seek > 0L) danmakuController.onSeek(seek)
        }
    }

    private fun handleDolbyVisionPaused() {
        if (playbackBackend != PlaybackBackend.MEDIA3) return
        ui = ui.copy(isPlaying = false)
        if (::danmakuController.isInitialized) danmakuController.onPaused(playerPositionMs())
    }

    private fun handleDolbyVisionBuffering(buffering: Boolean) {
        if (playbackBackend != PlaybackBackend.MEDIA3) return
        ui = ui.copy(isBuffering = buffering)
        if (::danmakuController.isInitialized) {
            danmakuController.onBuffering(buffering, playerPositionMs())
        }
    }

    private fun handleDolbyVisionEnded() {
        if (playbackBackend != PlaybackBackend.MEDIA3) return
        clearLiveSession("media3-ended")
        castActive = false
        current = null
        if (::danmakuController.isInitialized) danmakuController.close()
        ui = ui.copy(
            mode = TvMode.IDLE,
            destination = TvDestination.HOME,
            sidebarVisible = true,
            castTitle = "",
            qualityLabel = "",
            qualityDetail = "",
            qualitySource = "",
            audioLabel = "",
            isPlaying = false,
            isBuffering = false,
            isLive = false,
            liveFormat = "",
            liveCdn = "",
            liveBackupCount = 0,
            positionMs = 0,
            durationMs = 0,
            hudVisible = false,
            tracks = emptyList(),
            audios = emptyList(),
            toast = "播放结束",
            showContinue = ui.rememberPlaybackContent,
        )
    }

    private fun handleDolbyVisionError(error: PlaybackException) {
        if (playbackBackend != PlaybackBackend.MEDIA3) return
        val info = current
        lastErrorRetry = info?.let { currentInfo -> { play(currentInfo, playerPositionMs()) } }
        LogHub.e(TAG, "Media3 Dolby Vision failed: ${LogRedaction.reason(error)}", error)
        PlaybackDiagnostics.fail(
            code = "DOLBY_VISION_PLAYBACK_FAILED",
            message = "杜比视界播放失败",
            detail = "reason=${LogRedaction.reason(error)}",
        )
        showError(
            title = "杜比视界播放失败",
            message = "当前设备、系统或 HDMI 链路无法播放此杜比视界码流。",
            code = "DOLBY_VISION_PLAYBACK_FAILED",
            canRetry = info != null,
            keepVideo = true,
        )
        if (::danmakuController.isInitialized) danmakuController.close()
    }

    /** 将 VLC 播放器事件转换为界面状态，并记录关键转换。 */
    private fun handlePlayerEvent(event: MediaPlayer.Event) {
        if (playbackBackend != PlaybackBackend.VLC) return
        when (event.type) {
            MediaPlayer.Event.Playing -> {
                val seek = pendingSeekMs
                pendingSeekMs = 0L
                LogHub.i(TAG, "ui playing applySeek=${seek}ms")
                runOnUiThread {
                    ui = ui.copy(
                        mode = TvMode.PLAYING,
                        isPlaying = true,
                        isBuffering = false,
                        positionMs = if (seek > 0) seek else ui.positionMs,
                    )
                    if (seek > 0) {
                        playerEngine.seekTo(seek, "playing-apply-seek")
                    }
                    val duration = playerDurationMs()
                    if (duration > 0) {
                        ui = ui.copy(durationMs = duration)
                    }
                    refreshVideoSize()
                    if (::danmakuController.isInitialized) {
                        val position = if (seek > 0) {
                            seek
                        } else {
                            playerPositionMs()
                        }
                        danmakuController.onPlaying(position)
                        if (seek > 0) danmakuController.onSeek(seek)
                    }
                    if (current?.isLive == true) {
                        livePlaybackHealth.onPlaying()
                        liveStallWatchdog.reset()
                        completeLiveRouteSwitch()
                    }
                }
            }

            MediaPlayer.Event.Paused -> {
                LogHub.d(TAG, "ui paused")
                runOnUiThread {
                    ui = ui.copy(isPlaying = false)
                    if (::danmakuController.isInitialized) {
                        danmakuController.onPaused(
                            playerPositionMs(),
                        )
                    }
                }
            }

            MediaPlayer.Event.Stopped -> {
                LogHub.d(TAG, "ui stopped")
                runOnUiThread {
                    ui = ui.copy(isPlaying = false, isBuffering = false)
                    if (::danmakuController.isInitialized) danmakuController.close()
                }
            }

            MediaPlayer.Event.EndReached -> {
                LogHub.i(TAG, "ui end reached")
                clearLiveSession("vlc-end-reached")
                castActive = false
                current = null
                runOnUiThread {
                    if (::danmakuController.isInitialized) danmakuController.close()
                    ui = ui.copy(
                        mode = TvMode.IDLE,
                        destination = TvDestination.HOME,
                        sidebarVisible = true,
                        castTitle = "",
                        qualityLabel = "",
                        qualityDetail = "",
                        qualitySource = "",
                        audioLabel = "",
                        isPlaying = false,
                        isBuffering = false,
                        isLive = false,
                        liveFormat = "",
                        liveCdn = "",
                        liveBackupCount = 0,
                        positionMs = 0,
                        durationMs = 0,
                        hudVisible = false,
                        tracks = emptyList(),
                        audios = emptyList(),
                        toast = "播放结束",
                        showContinue = ui.rememberPlaybackContent,
                    )
                }
            }

            MediaPlayer.Event.EncounteredError -> {
                val snapshot = runCatching { playerEngine.stateSnapshot() }
                    .getOrDefault("(snapshot unavailable)")
                LogHub.e(TAG, "ui playback failed code=PLAYBACK_FAILED snapshot=$snapshot")
                val info = current
                if (info?.isLive == true) {
                    handleLivePlaybackFailure("player-error")
                    return
                }
                if (info != null) {
                    lastErrorRetry = {
                        play(info, playerPositionMs())
                    }
                }
                showError(
                    title = "播放失败",
                    message = "设备解码能力不足或网络异常。",
                    code = "PLAYBACK_FAILED",
                    canRetry = info != null,
                    keepVideo = true,
                )
                if (::danmakuController.isInitialized) danmakuController.close()
            }

            MediaPlayer.Event.Buffering -> {
                val buffering = event.buffering < 100f
                runOnUiThread {
                    ui = ui.copy(isBuffering = buffering)
                    if (current?.isLive == true &&
                        livePlaybackHealth.onBuffering(buffering)
                    ) {
                        handleLivePlaybackFailure("two-consecutive-rebuffers")
                    }
                    if (::danmakuController.isInitialized) {
                        danmakuController.onBuffering(
                            buffering,
                            playerPositionMs(),
                        )
                    }
                }
            }

            else -> Unit
        }
    }

    /** 安装全局崩溃记录器，把未捕获异常写入持久化诊断历史。 */
    private fun installCrashHandler() {
        if (crashHandlerInstalled) return
        crashHandlerInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                PlaybackDiagnostics.recordCrash(thread?.name ?: "unknown", throwable)
            }
            runCatching {
                LogHub.e(
                    TAG,
                    "uncaught exception thread=${thread?.name} ${LogRedaction.reason(throwable)}",
                    throwable,
                    stack = true,
                )
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            }
        }
    }

    /** 根页面连续两次返回退出；其他返回层级由界面导航处理。 */
    override fun finish() {
        if (homeBackExitGate.press(System.currentTimeMillis())) {
            super.finish()
        } else {
            Toast.makeText(this, HomeBackExitTiming.PROMPT_TEXT, Toast.LENGTH_SHORT).show()
        }
    }

    /** 将当前播放信息加入最近投屏记录。 */
    private fun addRecent(info: BiliApi.PlayInfo) {
        if (!ui.rememberPlaybackContent) return
        val list = recentCastStore.add(ui.recents, info)
        ui = ui.copy(recents = list)
    }

    /** 根据最近投屏记录重新解析并开始播放。 */
    private fun recast(recent: RecentCast) {
        if (resolving) {
            LogHub.w(TAG, "recast ignored: another resolve is running")
            return
        }
        PlaybackDiagnostics.beginSession("recast")
        PlaybackDiagnostics.attribute("cast.source", "recent")
        PlaybackDiagnostics.attribute("recent.aid", recent.aid.toString())
        PlaybackDiagnostics.attribute("recent.cid", recent.cid.toString())
        PlaybackDiagnostics.attribute("recent.title", recent.title)
        LogHub.i(TAG, "recast begin aid=${recent.aid} cid=${recent.cid} title=${recent.title.take(40)}")
        scope.launch {
            resolving = true
            ui = ui.copy(
                mode = TvMode.LOADING,
                castTitle = recent.title,
                destination = TvDestination.HOME,
                sidebarVisible = false,
                helpVisible = false,
                hudVisible = false,
            )
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    BiliApi.resolveByAidCid(recent.aid, recent.cid, recent.title)
                }
            }
            resolving = false
            result.onSuccess { play(it, 0L) }.onFailure {
                LogHub.e(
                    TAG,
                    "recast resolve failed: ${LogRedaction.reason(it)}",
                    it,
                    stack = true,
                )
                PlaybackDiagnostics.fail(
                    code = "RECAST_FAILED",
                    message = it.message ?: "视频信息已失效",
                    detail = "aid=${recent.aid} cid=${recent.cid} " +
                        "reason=${LogRedaction.reason(it)}",
                )
                showError(
                    title = "无法继续投屏",
                    message = it.message ?: "视频信息已失效，请从手机重新投屏。",
                    code = "RECAST_FAILED",
                    canRetry = false,
                )
            }
        }
    }

    // ------------------------------------------------------------------ //
    // login
    // ------------------------------------------------------------------ //
    /** 根据已有凭据进入登录状态或准备登录页面。 */
    private fun startOrLogin() {
        loginCoordinator.startOrLogin()
    }

    /** 启动二维码登录流程。 */
    private fun startQrLogin() {
        loginCoordinator.startQrLogin()
    }

    /** 进入访客模式并跳过登录。 */
    private fun skipLogin() {
        loginCoordinator.skipLogin()
    }

    /** 清除账号登录状态。 */
    private fun logout() {
        loginCoordinator.logout()
    }

    // ------------------------------------------------------------------ //
    // cast + resolve + play
    // ------------------------------------------------------------------ //
    /** 处理 DLNA 投屏请求并解析其媒体身份。 */
    private fun handleCast(uri: String, title: String, longDescription: String) {
        PlaybackDiagnostics.beginSession("cast")
        PlaybackDiagnostics.attribute("cast.source", "dlna")
        PlaybackDiagnostics.attribute("cast.uri", LogRedaction.url(uri))
        PlaybackDiagnostics.attribute("cast.title", title)
        PlaybackDiagnostics.attribute(
            "cast.longDescriptionBytes",
            longDescription.length.toString(),
        )
        LogHub.i(
            TAG,
            "cast received title=${title.take(80)} uri=${LogRedaction.url(uri)} " +
                "longDescription=${longDescription.length}B",
        )
        castActive = true
        lastTitle = title
        pendingPause = false
        lastNva = null
        lastErrorRetry = null
        LogHub.setCast(title)
        ui = ui.copy(
            mode = TvMode.LOADING,
            showContinue = ui.rememberPlaybackContent,
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
            loginQr = null,
            loginStatus = "",
            tracks = emptyList(),
            audios = emptyList(),
        )

        val nva = NvaExtDecoder.decode(longDescription)
        if (nva == null) {
            LogHub.e(TAG, "cast rejected: nva_ext decode failed")
            PlaybackDiagnostics.fail(
                code = "NVA_DECODE_FAILED",
                message = "无法识别这次投屏",
                detail = "longDescriptionBytes=${longDescription.length} " +
                    "metadataUri=${LogRedaction.url(uri)}",
            )
            dlna.markCastFailed("nva-decode-failed")
            castActive = false
            showError(
                title = "无法识别这次投屏",
                message = "请更新手机 B 站 App 后重新投屏。",
                code = "NVA_DECODE_FAILED",
                canRetry = false,
            )
            return
        }
        lastNva = nva
        val desiredSpeed = nva.userDesireSpeed
            ?.toFloatOrNull()
            ?.let(PlaybackSpeed::normalize)
            ?: 1f
        LogHub.i(
            TAG,
            "cast nva aid=${nva.aid} cid=${nva.cid} bvid=${nva.bvid} " +
                "eid=${nva.eid} sid=${nva.sid} room=${nva.roomId} " +
                "qn=${nva.qn} desireQn=${nva.userDesireQn} speed=$desiredSpeed " +
                "ct=${nva.ct} tp=${nva.tp}",
        )
        PlaybackDiagnostics.attribute("cast.aid", nva.aid.toString())
        PlaybackDiagnostics.attribute("cast.cid", nva.cid.toString())
        PlaybackDiagnostics.attribute("cast.bvid", nva.bvid.orEmpty())
        PlaybackDiagnostics.attribute("cast.eid", nva.eid.toString())
        PlaybackDiagnostics.attribute("cast.sid", nva.sid.toString())
        PlaybackDiagnostics.attribute("cast.roomId", nva.roomId.toString())
        PlaybackDiagnostics.attribute("cast.requestQn", nva.qn.toString())
        PlaybackDiagnostics.attribute("cast.desireQn", nva.userDesireQn.toString())
        PlaybackDiagnostics.attribute("cast.desireSpeed", desiredSpeed.toString())
        ui = ui.copy(playbackSpeed = desiredSpeed)
        resolveNvaAndPlay(nva, title)
    }

    /** 根据 nva 内容解析播放地址并开始播放。 */
    private fun resolveNvaAndPlay(nva: NvaExtDecoder.NvaExtContent, title: String) {
        resolveJob?.cancel()
        clearLiveSession("resolve-new-cast")
        resolving = true
        lastNva = nva
        lastTitle = title
        val branch = when {
            nva.hasUgc -> "ugc"
            nva.hasPgc && nva.eid > 0 -> "pgc"
            nva.hasPgc -> "pgc-without-epId"
            nva.hasLive -> "live"
            else -> "none"
        }
        LogHub.i(
            TAG,
            "resolve begin branch=$branch aid=${nva.aid} cid=${nva.cid} " +
                "bvid=${nva.bvid} eid=${nva.eid} room=${nva.roomId}",
        )
        PlaybackDiagnostics.attribute("resolve.branch", branch)
        ui = ui.copy(
            mode = TvMode.LOADING,
            showContinue = ui.rememberPlaybackContent,
            castTitle = title.ifBlank { "正在投屏" },
            error = null,
            hudVisible = true,
        )

        resolveJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    when {
                        nva.hasUgc -> CastResolution.Vod(
                            BiliApi.resolveByAidCid(nva.aid, nva.cid, title, nva.bvid ?: ""),
                        )
                        nva.hasPgc && nva.eid > 0 -> CastResolution.Vod(
                            BiliApi.resolvePgc(nva.eid, nva.cid, title),
                        )
                        nva.hasPgc ->
                            error("PGC season without epId is not supported")
                        nva.hasLive -> {
                            val token = authTokenStore.load()
                                ?: error("TV account login is required for live playback")
                            val liveInfo = LivePlayUrlResolver.resolve(
                                context = applicationContext,
                                accessKey = token.accessKey,
                                roomId = nva.roomId,
                            )
                            val routes = liveCdnCoordinator.rankInitial(liveInfo.qualityCandidates)
                            if (routes.isEmpty()) error("live response has no eligible CDN routes")
                            CastResolution.Live(liveInfo, routes)
                        }
                        else ->
                            error("no playable identity in nva_ext")
                    }
                }
            }
            if (!isActive) return@launch
            resolving = false
            result.onSuccess { resolved ->
                when (resolved) {
                    is CastResolution.Vod -> {
                        val info = resolved.info
                        LogHub.i(
                            TAG,
                            "resolved qn=${info.video.id} ${info.video.width}x${info.video.height} " +
                                "codec=${info.video.codecid} audio=${info.audio?.id} " +
                                "duration=${info.durationSec}s videos=${info.videos.size} " +
                                "audios=${info.audios.size} progressive=${info.progressiveUrl != null}",
                        )
                        PlaybackDiagnostics.attribute("resolve.videos", info.videos.size.toString())
                        PlaybackDiagnostics.attribute("resolve.audios", info.audios.size.toString())
                        PlaybackDiagnostics.attribute(
                            "resolve.progressive",
                            (info.progressiveUrl != null).toString(),
                        )
                        dlna.setDurationMs(info.durationSec * 1000)
                        play(info, 0L)
                    }
                    is CastResolution.Live -> startLivePlayback(resolved.info, resolved.routes, title)
                }
            }.onFailure { t ->
                val message = t.message.orEmpty()
                val live = nva.hasLive
                val pgc = message.contains("PGC season", ignoreCase = true)
                val code = when {
                    live -> "LIVE_RESOLVE_FAILED"
                    pgc -> "PGC_UNSUPPORTED"
                    else -> "RESOLVE_FAILED"
                }
                LogHub.e(
                    TAG,
                    "cast resolve failed code=$code: ${LogRedaction.reason(t)}",
                    t,
                    stack = true,
                )
                PlaybackDiagnostics.fail(
                    code = code,
                    message = message.ifBlank { "取流失败" },
                    detail = "branch=$branch aid=${nva.aid} cid=${nva.cid} " +
                        "reason=${LogRedaction.reason(t)}",
                )
                dlna.markCastFailed(code)
                if (!pgc) {
                    lastErrorRetry = { resolveNvaAndPlay(nva, title) }
                }
                showError(
                    title = when {
                        live -> "直播取流失败"
                        pgc -> "该内容暂不支持"
                        else -> "取流失败"
                    },
                    message = when {
                        live && message.contains("login", ignoreCase = true) ->
                            "请先在电视端登录 B 站账号后重试。"
                        live -> "未能获取当前账号可播放的直播源，请稍后重试。"
                        pgc -> "请在手机上选择具体剧集后重试。"
                        else -> "请检查网络或账号权益。"
                    },
                    code = code,
                    canRetry = !pgc,
                )
            }
        }
    }

    /** 采用首次线路测速结果启动直播，并记录备用线路状态。 */
    private fun startLivePlayback(
        stream: LivePlayInfo,
        routes: List<RankedLiveSource>,
        title: String,
    ) {
        val first = routes.firstOrNull { it.candidate.quality == stream.quality }
            ?: routes.firstOrNull()
            ?: run {
                showError(
                    title = "直播线路不可用",
                    message = "TV 接口没有返回可播放的直播线路。",
                    code = "LIVE_NO_CANDIDATE",
                    canRetry = true,
                )
                return
            }
        liveBackupRefreshJob?.cancel()
        livePlaybackHealth.reset()
        liveSwitchInProgress = false
        pendingLiveRouteSwitch = null
        liveStallWatchdog.reset()
        val session = LiveSession(
            stream = stream,
            routes = routes.toMutableList(),
            active = first.candidate,
        )
        session.attemptedUrls += first.candidate.url
        liveSession = session
        dlna.setDurationMs(0L)
        PlaybackDiagnostics.attribute("live.roomId", stream.roomId.toString())
        PlaybackDiagnostics.attribute("live.requestedQn", stream.requestedQuality.toString())
        PlaybackDiagnostics.attribute("live.actualQn", stream.quality.toString())
        PlaybackDiagnostics.attribute(
            "live.acceptedQn",
            stream.acceptedQualities.joinToString(","),
        )
        PlaybackDiagnostics.attribute("live.format", first.candidate.format)
        PlaybackDiagnostics.attribute("live.codec", first.candidate.codecName)
        PlaybackDiagnostics.attribute("live.cdn", first.candidate.cdnLabel)
        PlaybackDiagnostics.attribute("live.backupCount", (routes.size - 1).coerceAtLeast(0).toString())
        routes.forEachIndexed { index, route ->
            PlaybackDiagnostics.track(
                "live-route",
                mapOf(
                    "index" to index.toString(),
                    "quality" to route.candidate.quality.toString(),
                    "format" to route.candidate.format,
                    "codec" to route.candidate.codecName,
                    "host" to route.candidate.cdnLabel,
                    "pcdn" to route.candidate.pcdn.toString(),
                    "probe" to (route.result?.let {
                        "${it.success}:${it.firstByteMs}ms:${it.throughputBytesPerSecond}Bps"
                    } ?: "unprobed"),
                    "selected" to (route.candidate.url == first.candidate.url).toString(),
                ),
            )
        }
        LogHub.i(
            TAG,
            "live start room=${stream.roomId} requestedQn=${stream.requestedQuality} " +
                "actualQn=${stream.quality} accepted=${stream.acceptedQualities} " +
                "selected=${first.candidate.cdnLabel}/${first.candidate.format}/" +
                "${first.candidate.codecName} routes=${routes.size}",
        )
        if (first.candidate.pcdn) {
            LogHub.w(TAG, "live PCDN fallback selected because no official CDN route is available")
            PlaybackDiagnostics.event(
                "LivePcdnFallback",
                "room=${stream.roomId} quality=${stream.quality} host=${first.candidate.cdnLabel}",
            )
        }
        val started = play(livePlayInfo(session, first.candidate, title), 0L)
        if (started) startLiveBackupRefresh(session)
        else scope.launch { handleLiveRouteOpenFailure("initial-open-failed") }
    }

    /** 将一条直播源适配到共用播放器模型；直播时长、音轨和续播身份均为空。 */
    private fun livePlayInfo(
        session: LiveSession,
        candidate: LiveStreamCandidate,
        title: String,
    ): BiliApi.PlayInfo {
        val backups = session.routes.asSequence()
            .map { it.candidate }
            .filter { it.url != candidate.url && it.quality == candidate.quality }
            .map { it.url }
            .distinct()
            .toList()
        val track = BiliApi.Track(
            id = candidate.quality,
            codecid = candidate.codecid,
            width = candidate.width,
            height = candidate.height,
            bandwidth = candidate.bandwidth,
            url = candidate.url,
            backup = backups,
            pcdn = candidate.pcdn,
            mimeType = if (candidate.format == "flv") "video/x-flv" else "video/mp4",
            codecs = candidate.codecName,
        )
        return BiliApi.PlayInfo(
            title = title.ifBlank { "直播间 ${session.stream.roomId}" },
            bvid = "",
            aid = 0L,
            cid = session.stream.roomId,
            durationSec = 0L,
            video = track,
            audio = null,
            progressiveUrl = candidate.url,
            videos = listOf(track),
            audios = emptyList(),
            isLive = true,
            liveFormat = candidate.format,
            liveCdn = candidate.cdnLabel,
            liveBackupCount = backups.size,
        )
    }

    /** 每三十秒后台重测最多三条备用线路，更新后续切源排序但不动当前播放。 */
    private fun startLiveBackupRefresh(session: LiveSession) {
        liveBackupRefreshJob?.cancel()
        liveBackupRefreshJob = scope.launch {
            while (isActive && liveSession === session) {
                delay(LIVE_BACKUP_REFRESH_INTERVAL_MS)
                if (!isActive || liveSession !== session || !castActive) break
                val activeUrl = session.active.url
                val refresh = withContext(Dispatchers.IO) {
                    liveCdnCoordinator.refreshBackups(
                        candidates = session.stream.qualityCandidates,
                        excludedUrls = session.attemptedUrls + activeUrl,
                    )
                }
                if (liveSession !== session) break
                if (refresh.isEmpty()) continue
                val refreshedByUrl = refresh.associateBy { it.candidate.url }
                val remaining = session.routes
                    .filterNot { it.candidate.url == activeUrl }
                    .map { refreshedByUrl[it.candidate.url] ?: it }
                val official = remaining.filterNot { it.candidate.pcdn }
                val pcNodes = remaining.filter { it.candidate.pcdn }
                val successful = official.filter { it.result?.success == true }
                    .sortedWith(liveProbeOrder())
                val unmeasured = official.filter { it.result == null }
                val failed = official.filter { it.result?.success == false }
                    .sortedWith(liveProbeOrder())
                val successfulPcdn = pcNodes.filter { it.result?.success == true }
                    .sortedWith(liveProbeOrder())
                val unmeasuredPcdn = pcNodes.filter { it.result == null }
                val failedPcdn = pcNodes.filter { it.result?.success == false }
                    .sortedWith(liveProbeOrder())
                session.routes = (
                    successful + unmeasured + successfulPcdn + unmeasuredPcdn +
                        failed + failedPcdn
                    ).toMutableList()
                session.routes.add(0, RankedLiveSource(session.active))
                current = livePlayInfo(session, session.active, lastTitle)
                ui = ui.copy(liveBackupCount = current?.liveBackupCount ?: 0)
                PlaybackDiagnostics.attribute(
                    "live.backupCount",
                    (session.routes.size - 1).coerceAtLeast(0).toString(),
                )
                PlaybackDiagnostics.event(
                    "LiveBackupRefresh",
                    refresh.joinToString(";") { route ->
                        "host=${route.candidate.cdnLabel} format=${route.candidate.format} " +
                            "codec=${route.candidate.codecName} " +
                            "firstByte=${route.result?.firstByteMs ?: -1}ms " +
                            "throughput=${route.result?.throughputBytesPerSecond ?: 0}Bps " +
                            "success=${route.result?.success}"
                    },
                )
                LogHub.i(
                    TAG,
                    "live backup refresh room=${session.stream.roomId} " +
                        "active=${session.active.cdnLabel} " +
                        "ranked=${refresh.joinToString(",") { it.candidate.cdnLabel }} " +
                        "backupCount=${session.routes.size - 1}",
                )
            }
        }
    }

    /** 播放器的同步 open 失败时立即试下一条同画质线路。 */
    private suspend fun handleLiveRouteOpenFailure(reason: String) {
        val session = liveSession ?: return
        try {
            while (liveSession === session && castActive) {
                val next = liveCdnCoordinator.nextSameQuality(
                    ordered = session.routes,
                    quality = session.stream.quality,
                    attemptedUrls = session.attemptedUrls,
                )
                if (next == null) {
                    continueLiveFallback(reason)
                    return
                }
                if (switchLiveRoute(next, reason, null)) return
                LogHub.e(
                    TAG,
                    "live candidate open failed room=${session.stream.roomId} " +
                        "quality=${session.stream.quality} host=${next.candidate.cdnLabel} " +
                        "format=${next.candidate.format} codec=${next.candidate.codecName}",
                )
            }
        } catch (failure: Throwable) {
            LogHub.e(
                TAG,
                "live route open recovery failed room=${session.stream.roomId} " +
                    "error=${failure.javaClass.simpleName}",
            )
            failLivePlayback("LIVE_ROUTE_OPEN_FAILED", "直播线路无法打开")
        }
    }

    /** 处理播放器运行期错误、缓冲或停滞并切换同档线路。 */
    private fun handleLivePlaybackFailure(reason: String) {
        if (liveSwitchInProgress || liveSession == null || !castActive) return
        liveSwitchInProgress = true
        scope.launch {
            try {
                val session = liveSession ?: return@launch
                val failed = session.active
                val snapshot = runCatching { playerEngine.stateSnapshot() }
                    .getOrDefault("(snapshot unavailable)")
                LogHub.e(
                    TAG,
                    "live playback failure room=${session.stream.roomId} reason=$reason " +
                        "quality=${session.stream.quality} format=${failed.format} " +
                        "codec=${failed.codecName} host=${failed.cdnLabel} snapshot=$snapshot",
                )
                PlaybackDiagnostics.event(
                    "LiveRouteFailure",
                    "room=${session.stream.roomId} reason=$reason quality=${session.stream.quality} " +
                        "format=${failed.format} codec=${failed.codecName} " +
                        "host=${failed.cdnLabel} failure=player-event",
                )
                pendingLiveRouteSwitch = null
                val sameCodec = session.routes.firstOrNull { route ->
                    route.candidate.quality == session.stream.quality &&
                        route.candidate.codecid == failed.codecid &&
                        route.candidate.url !in session.attemptedUrls
                }
                if (sameCodec != null) {
                    if (!switchLiveRoute(sameCodec, reason, failed)) {
                        handleLiveRouteOpenFailure("route-open-failed:$reason")
                    }
                } else {
                    continueLiveFallback(reason)
                }
            } catch (failure: Throwable) {
                LogHub.e(
                    TAG,
                    "live route switch failed reason=$reason " +
                        "error=${failure.javaClass.simpleName}",
                )
                PlaybackDiagnostics.fail(
                    code = "LIVE_ROUTE_SWITCH_FAILED",
                    message = "直播线路切换失败",
                    detail = "reason=$reason error=${failure.javaClass.simpleName}",
                )
                failLivePlayback("LIVE_ROUTE_SWITCH_FAILED", "直播线路切换失败")
            } finally {
                liveSwitchInProgress = false
            }
        }
    }

    /** 切换到已排序的同档线路，并保留用户当前的暂停状态。 */
    private fun switchLiveRoute(
        target: RankedLiveSource,
        reason: String,
        from: LiveStreamCandidate?,
    ): Boolean {
        val session = liveSession ?: return false
        if (target.candidate.url in session.attemptedUrls) return false
        val prior = from ?: session.active
        session.attemptedUrls += target.candidate.url
        session.active = target.candidate
        liveStallWatchdog.reset()
        pendingLiveRouteSwitch = PendingLiveRouteSwitch(
            reason = reason,
            from = prior,
            target = target.candidate,
            firstByteMs = target.result?.firstByteMs,
            throughputBytesPerSecond = target.result?.throughputBytesPerSecond,
        )
        val stayPaused = !ui.isPlaying && !ui.isBuffering && !playerIsPlaying()
        pendingPause = stayPaused
        val info = livePlayInfo(session, target.candidate, lastTitle)
        LogHub.i(
            TAG,
            "live switch attempt reason=$reason room=${session.stream.roomId} " +
                "quality=${session.stream.quality} " +
                "from=${prior.cdnLabel}/${prior.format}/${prior.codecName} " +
                "to=${target.candidate.cdnLabel}/${target.candidate.format}/" +
                "${target.candidate.codecName} firstByte=${target.result?.firstByteMs ?: -1}ms " +
                "throughput=${target.result?.throughputBytesPerSecond ?: 0}Bps " +
                "failure=${target.result?.failure.orEmpty()}",
        )
        PlaybackDiagnostics.event(
            "LiveRouteSwitchAttempt",
            "reason=$reason room=${session.stream.roomId} quality=${session.stream.quality} " +
                "from=${prior.cdnLabel}/${prior.format}/${prior.codecName} " +
                "to=${target.candidate.cdnLabel}/${target.candidate.format}/" +
                "${target.candidate.codecName} firstByte=${target.result?.firstByteMs ?: -1}ms " +
                "throughput=${target.result?.throughputBytesPerSecond ?: 0}Bps " +
                "failure=${target.result?.failure.orEmpty()}",
        )
        if (target.candidate.pcdn) {
            LogHub.w(
                TAG,
                "live PCDN fallback activated after official routes exhausted " +
                    "host=${target.candidate.cdnLabel} quality=${session.stream.quality}",
            )
            PlaybackDiagnostics.event(
                "LivePcdnFallback",
                "reason=$reason room=${session.stream.roomId} " +
                    "quality=${session.stream.quality} host=${target.candidate.cdnLabel}",
            )
        }
        val started = play(info, 0L)
        if (!started) {
            pendingLiveRouteSwitch = null
            PlaybackDiagnostics.event(
                "LiveRouteOpenFailure",
                "room=${session.stream.roomId} reason=$reason " +
                    "quality=${session.stream.quality} format=${target.candidate.format} " +
                    "codec=${target.candidate.codecName} host=${target.candidate.cdnLabel}",
            )
        }
        return started
    }

    /** 没有同档线路时先尝试 HEVC→AVC，再按服务端公布的档位逐级重新解析。 */
    private suspend fun continueLiveFallback(reason: String) {
        val session = liveSession ?: return
        val active = session.active
        if (active.codecid == 12 && !session.avcFallbackAttempted) {
            session.avcFallbackAttempted = true
            resolveLiveFallback(session, session.stream.quality, forceAvc = true, reason = "hevc-fallback:$reason")
            return
        }
        val lowerQuality = LivePlayUrlResolver.fallbackQualities(
            currentQuality = session.stream.quality,
            acceptedQualities = session.stream.acceptedQualities,
        ).firstOrNull { it !in session.downshiftedQualities }
        if (lowerQuality != null) {
            session.downshiftedQualities += lowerQuality
            resolveLiveFallback(session, lowerQuality, forceAvc = active.codecid == 7, reason = "quality-downshift:$reason")
            return
        }
        failLivePlayback(
            code = "LIVE_SOURCES_EXHAUSTED",
            message = "当前账号画质下的直播线路均不可用。",
        )
    }

    /** 重新请求 AVC 或服务端认可的较低档位，并在新候选中完成首播选路。 */
    private suspend fun resolveLiveFallback(
        session: LiveSession,
        quality: Int,
        forceAvc: Boolean,
        reason: String,
    ) {
        val token = authTokenStore.load() ?: error("TV login expired during live route fallback")
        val resolved = withContext(Dispatchers.IO) {
            val live = LivePlayUrlResolver.resolve(
                context = applicationContext,
                accessKey = token.accessKey,
                roomId = session.stream.roomId,
                quality = quality,
                forceAvc = forceAvc,
            )
            live to liveCdnCoordinator.rankInitial(live.qualityCandidates)
        }
        if (liveSession !== session || !castActive) return
        if (resolved.second.isEmpty()) error("live fallback returned no routes")
        val actual = resolved.first
        if (quality < session.stream.quality && actual.quality >= session.stream.quality) {
            val nextQuality = LivePlayUrlResolver.fallbackQualities(
                currentQuality = session.stream.quality,
                acceptedQualities = session.stream.acceptedQualities,
            ).firstOrNull { it !in session.downshiftedQualities }
            LogHub.w(
                TAG,
                "live quality fallback request not applied room=${session.stream.roomId} " +
                    "requested=$quality actual=${actual.quality} next=${nextQuality ?: "none"}",
            )
            PlaybackDiagnostics.event(
                "LiveQualityFallbackRejected",
                "room=${session.stream.roomId} requested=$quality actual=${actual.quality} " +
                    "next=${nextQuality ?: "none"}",
            )
            if (nextQuality != null) {
                session.downshiftedQualities += nextQuality
                resolveLiveFallback(
                    session = session,
                    quality = nextQuality,
                    forceAvc = session.active.codecid == 7,
                    reason = "quality-downshift:$reason",
                )
            } else {
                failLivePlayback(
                    "LIVE_QUALITY_DOWNGRADE_REJECTED",
                    "服务器未提供更低画质的直播流。",
                )
            }
            return
        }
        val old = session.active
        session.stream = actual
        session.routes = resolved.second.toMutableList()
        session.attemptedUrls.clear()
        session.avcFallbackAttempted = forceAvc
        val next = session.routes.firstOrNull { it.candidate.quality == actual.quality }
            ?: session.routes.first()
        PlaybackDiagnostics.event(
            "LiveQualityFallback",
            "reason=$reason room=${actual.roomId} requested=${actual.requestedQuality} " +
                "actual=${actual.quality} from=${old.cdnLabel}/${old.format}/${old.codecName} " +
                "to=${next.candidate.cdnLabel}/${next.candidate.format}/" +
                "${next.candidate.codecName}",
        )
        LogHub.w(
            TAG,
            "live re-resolved reason=$reason requested=${actual.requestedQuality} " +
                "actual=${actual.quality} forceAvc=$forceAvc " +
                "selected=${next.candidate.cdnLabel}/${next.candidate.format}/" +
                "${next.candidate.codecName}",
        )
        if (!switchLiveRoute(next, reason, old)) {
            handleLiveRouteOpenFailure("fallback-open-failed:$reason")
        }
    }

    /** 线路和已公布的降级档位均耗尽时展示可重试错误。 */
    private fun failLivePlayback(code: String, message: String) {
        val session = liveSession ?: return
        LogHub.e(
            TAG,
            "live playback exhausted room=${session.stream.roomId} qn=${session.stream.quality} " +
                "attempted=${session.attemptedUrls.size} reason=$code",
        )
        PlaybackDiagnostics.fail(
            code = code,
            message = message,
            detail = "room=${session.stream.roomId} quality=${session.stream.quality} " +
                "attempted=${session.attemptedUrls.size} active=${session.active.cdnLabel}/" +
                "${session.active.format}/${session.active.codecName}",
        )
        val nva = lastNva
        if (nva != null) lastErrorRetry = { resolveNvaAndPlay(nva, lastTitle) }
        showError(
            title = "直播播放失败",
            message = message,
            code = code,
            canRetry = nva != null,
        )
    }

    /** 线路成功进入 Playing 后记录结果并复用现有右下角 toast。 */
    private fun completeLiveRouteSwitch() {
        val switched = pendingLiveRouteSwitch ?: return
        pendingLiveRouteSwitch = null
        val session = liveSession ?: return
        LogHub.i(
            TAG,
            "live switch succeeded reason=${switched.reason} room=${session.stream.roomId} " +
                "quality=${session.stream.quality} " +
                "from=${switched.from?.cdnLabel ?: "(initial)"}/" +
                "${switched.from?.format.orEmpty()}/${switched.from?.codecName.orEmpty()} " +
                "to=${switched.target.cdnLabel}/${switched.target.format}/" +
                "${switched.target.codecName} firstByte=${switched.firstByteMs ?: -1}ms " +
                "throughput=${switched.throughputBytesPerSecond ?: 0}Bps",
        )
        PlaybackDiagnostics.event(
            "LiveRouteSwitchSuccess",
            "reason=${switched.reason} room=${session.stream.roomId} " +
                "quality=${session.stream.quality} " +
                "from=${switched.from?.cdnLabel ?: "(initial)"} " +
                "to=${switched.target.cdnLabel}/${switched.target.format}/" +
                "${switched.target.codecName} firstByte=${switched.firstByteMs ?: -1}ms " +
                "throughput=${switched.throughputBytesPerSecond ?: 0}Bps",
        )
        ui = ui.copy(
            toast = if (switched.reason.startsWith("user-quality")) {
                "已切换至 ${liveQualityLabel(session.stream.quality)}"
            } else {
                "已自动切换至更流畅线路"
            },
        )
    }

    /** 清理直播专属任务和阈值，防止用户停止后触发后台自动重连。 */
    private fun clearLiveSession(reason: String) {
        val session = liveSession
        liveBackupRefreshJob?.cancel()
        liveBackupRefreshJob = null
        liveSession = null
        liveSwitchInProgress = false
        pendingLiveRouteSwitch = null
        livePlaybackHealth.reset()
        liveStallWatchdog.reset()
        if (session != null) LogHub.i(TAG, "live session cleared room=${session.stream.roomId} reason=$reason")
    }

    /** 直播 qn 与点播 qn 数值体系不同，使用直播清晰度名称。 */
    private fun liveQualityLabel(qn: Int): String = when (qn) {
        10_000 -> "原画"
        400 -> "蓝光"
        250 -> "超清"
        150 -> "高清"
        80 -> "流畅"
        else -> "直播 qn=$qn"
    }

    /** 直播可选画质档位，取服务端公布的可用档位并映射为友好名称。 */
    private fun liveQualityOptions(session: LiveSession): List<LiveQualityOption> {
        val available = (session.stream.acceptedQualities + session.stream.quality)
            .distinct()
            .sortedDescending()
        return available.mapNotNull { qn ->
            val label = liveQualityLabel(qn)
            if (label.startsWith("直播 qn=")) null else LiveQualityOption(qn = qn, label = label)
        }
    }

    private fun liveProbeOrder(): Comparator<RankedLiveSource> =
        compareBy<RankedLiveSource> { it.result?.success != true }
            .thenBy { it.result?.firstByteMs ?: Long.MAX_VALUE }
            .thenByDescending { it.result?.throughputBytesPerSecond ?: 0L }
            .thenBy { it.candidate.responseIndex }

    /** 使用指定播放信息打开播放器并更新界面状态。 */
    private fun play(info: BiliApi.PlayInfo, seekToMs: Long): Boolean {
        if (!::playerEngine.isInitialized) {
            LogHub.e(
                TAG,
                "play ignored: player engine unavailable aid=${info.aid} cid=${info.cid}",
            )
            PlaybackDiagnostics.fail(
                code = "PLAYER_UNAVAILABLE",
                message = "播放器未初始化",
                detail = "aid=${info.aid} cid=${info.cid} title=${info.title}",
            )
            showError(
                title = "播放器不可用",
                message = "应用未能初始化播放器，请重启应用后重试；如仍失败请查看诊断日志。",
                code = "PLAYER_UNAVAILABLE",
                canRetry = false,
            )
            return false
        }
        current = info
        castActive = true
        lastTitle = info.title
        lastErrorRetry = null
        pendingSeekMs = seekToMs.coerceAtLeast(0L)
        LogHub.i(
            TAG,
            "play begin qn=${info.video.id} codec=${info.video.codecid} " +
                "${info.video.width}x${info.video.height} audio=${info.audio?.id} " +
                "seek=${seekToMs}ms progressive=${info.progressiveUrl != null}",
        )

        ui = ui.copy(
            mode = TvMode.PLAYING,
            showContinue = ui.rememberPlaybackContent,
            castTitle = info.title,
            qualityLabel = if (info.isLive) liveQualityLabel(info.video.id) else info.qualityLabel,
            qualityDetail = if (info.isLive) {
                info.video.codecs.ifBlank { VlcTrackFormat.codecName(info.video.codecid) }
            } else {
                info.qualityDetail
            },
            qualitySource = if (info.isLive) "直播" else if (info.video.id >= 80) "已提升" else "",
            audioLabel = if (info.isLive) "自动音轨" else info.audioLabel,
            aid = info.aid,
            cid = info.cid,
            isPlaying = true,
            isBuffering = false,
            isLive = info.isLive,
            liveFormat = info.liveFormat,
            liveCdn = info.liveCdn,
            liveBackupCount = info.liveBackupCount,
            liveQualities = if (info.isLive) {
                liveSession?.let { liveQualityOptions(it) } ?: emptyList()
            } else {
                emptyList()
            },
            positionMs = if (info.isLive) 0L else seekToMs.coerceAtLeast(0L),
            durationMs = if (info.isLive) 0L else info.durationSec * 1000,
            selectedQn = info.video.id,
            selectedAudioId = info.audio?.id ?: 0,
            tracks = TrackOptionFactory.tracks(info),
            audios = TrackOptionFactory.audios(info),
            hudVisible = true,
            panel = null,
            error = null,
            stopCastConfirm = false,
        )
        if (!info.isLive) addRecent(info)

        val started = if (info.video.isDolbyVision) {
            // 先切换状态再停止 VLC，忽略旧播放器随后送达的 Stopped 事件。
            playbackBackend = PlaybackBackend.MEDIA3
            playerEngine.stopPlayback("switch-to-media3-dolby-vision")
            videoLayout.visibility = View.GONE
            dolbyVisionPlayerView.visibility = View.VISIBLE
            runCatching {
                dolbyVisionEngine().open(info, seekToMs)
                true
            }.onFailure { error ->
                LogHub.e(TAG, "open Dolby Vision Media3 failed", error)
            }.getOrDefault(false)
        } else {
            // Media3 的 Surface 必须先隐藏，避免两个视频输出竞争同一窗口合成层。
            dolbyVisionPlayer?.stop()
            playbackBackend = PlaybackBackend.VLC
            dolbyVisionPlayerView.visibility = View.GONE
            videoLayout.visibility = View.VISIBLE
            playerEngine.open(info, seekToMs)
        }
        if (!started) {
            if (info.isLive) {
                LogHub.e(
                    TAG,
                    "live player open rejected room=${info.cid} qn=${info.video.id} " +
                        "format=${info.liveFormat} codec=${info.video.codecid} " +
                        "host=${LogRedaction.host(info.progressiveUrl)}",
                )
                PlaybackDiagnostics.fail(
                    code = "LIVE_PLAYER_OPEN_FAILED",
                    message = "直播播放器无法打开当前线路",
                    detail = "room=${info.cid} qn=${info.video.id} " +
                        "format=${info.liveFormat} codec=${info.video.codecid} " +
                        "host=${LogRedaction.host(info.progressiveUrl)}",
                )
                return false
            }
            lastErrorRetry = { play(info, seekToMs) }
            showError(
                title = "播放失败",
                message = "无法打开该视频。诊断日志已记录具体原因。",
                code = "PLAYER_OPEN_FAILED",
                canRetry = true,
                keepVideo = false,
            )
            return false
        }

        if (::danmakuController.isInitialized) {
            if (info.isLive) {
                danmakuController.close()
            } else {
                danmakuController.open(info.aid, info.cid, info.durationSec * 1000, seekToMs)
                danmakuController.onPlaybackRateChanged(ui.playbackSpeed)
                danmakuController.applySettings(danmakuSettings)
                layoutDanmaku()
            }
        }
        if (info.isLive) {
            setPlayerPlaybackRate(1f, "live-normal-rate")
            ui = ui.copy(playbackSpeed = 1f)
        } else {
            setPlayerPlaybackRate(ui.playbackSpeed, "play-restore-rate")
        }

        if (pendingPause) {
            pendingPause = false
            setPlayerPaused(true, "pending-pause")
            ui = ui.copy(isPlaying = false)
        }
        LogHub.setPlay(describe(info))
        LogHub.i(TAG, "play opened qn=${info.video.id} ${info.video.width}x${info.video.height}")
        return true
    }

    /** 切换当前视频清晰度并从当前位置继续播放。 */
    private fun switchQuality(qn: Int) {
        if (current?.isLive == true) {
            switchLiveQuality(qn)
            return
        }
        val info = current
        if (info == null) {
            LogHub.w(TAG, "switchQuality qn=$qn ignored: no current media")
            return
        }
        val track = info.videos.firstOrNull { it.id == qn }
        if (track == null) {
            LogHub.w(
                TAG,
                "switchQuality qn=$qn not found; available=${info.videos.joinToString(",") { it.id.toString() }}",
            )
            return
        }
        val position = playerPositionMs()
        LogHub.i(
            TAG,
            "switchQuality qn=$qn codec=${track.codecid} ${track.width}x${track.height} " +
                "host=${LogRedaction.host(track.url)} at=${position}ms",
        )
        PlaybackDiagnostics.event(
            "QualitySwitch",
            "qn=$qn codec=${track.codecid} size=${track.width}x${track.height} " +
                "host=${LogRedaction.host(track.url)} at=${position}ms",
        )
        play(info.copy(video = track), position)
        ui = ui.copy(
            panel = PanelKind.QUALITY,
            qualitySource = "手动",
            toast = "已切换 ${BiliApi.qnLabel(qn)}",
        )
    }

    /** 按用户选择重新解析并播放指定直播画质。 */
    private fun switchLiveQuality(qn: Int) {
        val session = liveSession ?: return
        if (!castActive) return
        if (qn == session.stream.quality) {
            ui = ui.copy(toast = "已是当前画质")
            return
        }
        if (liveSwitchInProgress) return
        liveSwitchInProgress = true
        scope.launch {
            try {
                val token = authTokenStore.load()
                    ?: error("TV login expired during live quality switch")
                val forceAvc = session.active.codecid == 7
                val resolved = withContext(Dispatchers.IO) {
                    val live = LivePlayUrlResolver.resolve(
                        context = applicationContext,
                        accessKey = token.accessKey,
                        roomId = session.stream.roomId,
                        quality = qn,
                        forceAvc = forceAvc,
                    )
                    live to liveCdnCoordinator.rankInitial(live.qualityCandidates)
                }
                if (liveSession !== session || !castActive) return@launch
                if (resolved.second.isEmpty()) error("live quality switch returned no routes")
                val actual = resolved.first
                val old = session.active
                session.stream = actual
                session.routes = resolved.second.toMutableList()
                session.attemptedUrls.clear()
                session.avcFallbackAttempted = forceAvc
                session.downshiftedQualities.clear()
                val next = session.routes.firstOrNull { it.candidate.quality == actual.quality }
                    ?: session.routes.first()
                PlaybackDiagnostics.event(
                    "LiveQualitySwitch",
                    "requested=$qn actual=${actual.quality} " +
                        "from=${old.cdnLabel}/${old.format}/${old.codecName} " +
                        "to=${next.candidate.cdnLabel}/${next.candidate.format}/" +
                        "${next.candidate.codecName}",
                )
                LogHub.i(
                    TAG,
                    "live quality switch requested=$qn actual=${actual.quality} " +
                        "selected=${next.candidate.cdnLabel}/${next.candidate.format}/" +
                        "${next.candidate.codecName}",
                )
                if (!switchLiveRoute(next, "user-quality", old)) {
                    handleLiveRouteOpenFailure("quality-switch-open-failed")
                }
            } catch (failure: Throwable) {
                LogHub.e(
                    TAG,
                    "live quality switch failed qn=$qn " +
                        "error=${failure.javaClass.simpleName}",
                )
                PlaybackDiagnostics.event(
                    "LiveQualitySwitchFailed",
                    "requested=$qn error=${failure.javaClass.simpleName}",
                )
                ui = ui.copy(toast = "画质切换失败")
            } finally {
                liveSwitchInProgress = false
            }
        }
    }

    /** 切换当前音轨并从当前位置继续播放。 */
    private fun switchAudio(id: Int) {
        if (current?.isLive == true) {
            LogHub.w(TAG, "manual audio switch ignored for live id=$id")
            return
        }
        val info = current
        if (info == null) {
            LogHub.w(TAG, "switchAudio id=$id ignored: no current media")
            return
        }
        val track = info.audios.firstOrNull { it.id == id }
        if (track == null) {
            LogHub.w(
                TAG,
                "switchAudio id=$id not found; available=${info.audios.joinToString(",") { it.id.toString() }}",
            )
            return
        }
        val position = playerPositionMs()
        LogHub.i(
            TAG,
            "switchAudio id=$id host=${LogRedaction.host(track.url)} at=${position}ms",
        )
        PlaybackDiagnostics.event(
            "AudioSwitch",
            "id=$id host=${LogRedaction.host(track.url)} at=${position}ms",
        )
        play(info.copy(audio = track), position)
        ui = ui.copy(
            panel = PanelKind.AUDIO,
            toast = "已切换 ${BiliApi.audioLabelFor(id)}",
        )
    }

    /** 切换播放器的播放与暂停状态。 */
    private fun togglePlayPause() {
        if (!::playerEngine.isInitialized) {
            LogHub.w(TAG, "togglePlayPause ignored: player engine unavailable")
            return
        }
        if (ui.mode != TvMode.PLAYING && ui.mode != TvMode.LOADING) {
            LogHub.d(TAG, "togglePlayPause ignored: mode=${ui.mode}")
            return
        }
        val playing = playerIsPlaying()
        setPlayerPaused(playing, "remote-toggle")
        ui = ui.copy(
            isPlaying = playerIsPlaying(),
            hudVisible = true,
        )
    }

    /** 按指定毫秒数调整当前播放位置。 */
    private fun seekBy(ms: Long) {
        if (current?.isLive == true) {
            LogHub.w(TAG, "seek ignored for live delta=${ms}ms")
            return
        }
        if (!::playerEngine.isInitialized) {
            LogHub.w(TAG, "seekBy ignored: player engine unavailable")
            return
        }
        val length = playerDurationMs()
        val max = if (length > 0) length else Long.MAX_VALUE
        val currentPosition = playerPositionMs()
        val target = (currentPosition + ms).coerceIn(0L, max)
        if (length <= 0) {
            LogHub.w(TAG, "seekBy with unknown length; target=$target ms")
        }
        seekPlayer(target, "remote-seek")
        if (::danmakuController.isInitialized) {
            danmakuController.onSeek(target)
        }
        ui = ui.copy(positionMs = target, hudVisible = true)
    }

    /**
     * 预览性地跳转到新位置。
     *
     * 每次方向键输入都会立即驱动 VLC 跳转，以便画面加载目标位置；待确认状态只用于
     * 控制进度条显示与自动提交，不会延后播放器的实际定位。
     */
    private fun previewSeekBy(ms: Long) {
        if (current?.isLive == true) return
        if (!::playerEngine.isInitialized || ui.mode != TvMode.PLAYING) return

        val duration = playerDurationMs()
            .takeIf { it > 0L }
            ?: ui.durationMs
        if (duration <= 0L) {
            LogHub.w(TAG, "previewSeekBy ignored: unknown duration")
            return
        }
        val basePosition = ui.seekPreviewPositionMs
            ?: playerPositionMs()
        val target = (basePosition + ms).coerceIn(0L, duration)
        seekPlayer(target, "remote-seek-preview")
        if (::danmakuController.isInitialized) {
            danmakuController.onSeek(target)
        }
        ui = ui.copy(
            positionMs = target,
            durationMs = duration,
            seekPreviewPositionMs = target,
        )
    }

    /** 结束当前预览会话；播放器已在预览期间定位到目标位置。 */
    private fun confirmPreviewSeek() {
        if (current?.isLive != true && ui.seekPreviewPositionMs != null) {
            ui = ui.copy(seekPreviewPositionMs = null)
        }
    }

    /** 将 VLC 与弹幕时钟切换到相同的标准播放速度。 */
    private fun setPlaybackSpeed(rate: Float) {
        if (current?.isLive == true) {
            LogHub.w(TAG, "playback speed change ignored for live rate=$rate")
            ui = ui.copy(toast = "直播不支持倍速")
            return
        }
        if (!::playerEngine.isInitialized ||
            (ui.mode != TvMode.PLAYING && ui.mode != TvMode.LOADING)
        ) {
            LogHub.w(TAG, "setPlaybackSpeed ignored: player unavailable or mode=${ui.mode}")
            return
        }
        val target = PlaybackSpeed.normalize(rate)
        val applied = setPlayerPlaybackRate(target, "remote-speed")
        if (::danmakuController.isInitialized) {
            danmakuController.onPlaybackRateChanged(applied)
        }
        ui = ui.copy(
            playbackSpeed = applied,
            hudVisible = true,
            toast = "播放速度 ${PlaybackSpeed.label(applied)}",
        )
    }

    /** 更新弹幕设置并持久化。 */
    private fun updateDanmakuSettings(newSettings: DanmakuSettings) {
        danmakuSettings = newSettings
        danmakuPreferences.save(newSettings)
        if (::danmakuController.isInitialized) {
            danmakuController.applySettings(newSettings)
        }
        if (::danmakuView.isInitialized) {
            danmakuView.visibility = if (newSettings.enabled) View.VISIBLE else View.GONE
        }
        ui = ui.copy(
            danmakuEnabled = newSettings.enabled,
            danmakuAiShield = newSettings.aiShield,
            danmakuAreaRatio = newSettings.areaRatio,
            danmakuMaxOnScreen = newSettings.maxOnScreen,
            danmakuOpacity = newSettings.opacity,
            danmakuFontScale = newSettings.fontScale,
            danmakuDurationSec = newSettings.durationSec,
        )
        layoutDanmaku()
    }

    /** 按实际视频渲染容器和显示区域摆放弹幕视图。 */
    private fun layoutDanmaku() {
        if (!::videoLayout.isInitialized || !::danmakuView.isInitialized) return
        val screenW = videoLayout.width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        val screenH = videoLayout.height.takeIf { it > 0 }
            ?: resources.displayMetrics.heightPixels
        if (screenW <= 0 || screenH <= 0) return

        // VLCVideoLayout 已经是视频的实际输出容器，可能包含旋转、像素宽高比与
        // 缩放策略。轨道原始分辨率只用于信息展示，不能拿来反推弹幕视口。
        val areaH = (screenH * danmakuSettings.areaRatio).toInt().coerceIn(1, screenH)
        val params = danmakuView.layoutParams as? FrameLayout.LayoutParams
            ?: FrameLayout.LayoutParams(screenW, areaH)
        params.width = screenW
        params.height = areaH
        params.leftMargin = 0
        params.topMargin = 0
        params.gravity = Gravity.TOP or Gravity.START
        danmakuView.layoutParams = params
        if (::danmakuController.isInitialized) {
            danmakuController.setContentHeight(screenH)
            val expectedWidth = screenW
            val expectedHeight = areaH
            danmakuView.post {
                if (danmakuView.width != expectedWidth || danmakuView.height != expectedHeight) {
                    return@post
                }
                LogHub.d(TAG, "prepare danmaku viewport=${expectedWidth}x$expectedHeight")
                danmakuController.prepare()
            }
        }
    }

    /** 从播放器读取视频尺寸，变化时重新摆放弹幕视图。 */
    private fun refreshVideoSize() {
        if (playbackBackend == PlaybackBackend.MEDIA3) return
        if (!::playerEngine.isInitialized) return
        val size = playerEngine.videoSize() ?: return
        val liveCodec = current?.takeIf { it.isLive }?.video?.codecs
        val detail = if (liveCodec.isNullOrBlank()) {
            ui.qualityDetail
        } else {
            "${size.first}x${size.second} · $liveCodec"
        }
        if (size.first == videoWidth && size.second == videoHeight && detail == ui.qualityDetail) return
        videoWidth = size.first
        videoHeight = size.second
        ui = ui.copy(
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            qualityDetail = detail,
        )
        PlaybackDiagnostics.attribute("stream.size", "${size.first}x${size.second}")
        if (liveCodec != null) {
            PlaybackDiagnostics.attribute("live.videoSize", "${size.first}x${size.second}")
            current?.let { LogHub.setPlay(describe(it)) }
        }
        layoutDanmaku()
    }

    /** 设置播放器音量并同步界面状态。 */
    private fun setVolume(volume: Int) {
        if (!::playerEngine.isInitialized) {
            LogHub.w(TAG, "setVolume ignored: player engine unavailable")
            return
        }
        val v = volume.coerceIn(0, 100)
        setPlayerVolume(v, "remote-volume")
        ui = ui.copy(volume = v, hudVisible = true)
    }

    /** 重试最近一次失败的解析或播放操作。 */
    private fun retryLastAction() {
        val action = lastErrorRetry
        lastErrorRetry = null
        LogHub.i(
            TAG,
            "retry requested hasAction=${action != null} hasNva=${lastNva != null} " +
                "current=${current != null}",
        )
        if (action != null) {
            ui = ui.copy(
                mode = if (current != null) TvMode.PLAYING else TvMode.LOADING,
                error = null,
                hudVisible = true,
            )
            action()
            return
        }
        val nva = lastNva
        if (nva != null) {
            resolveNvaAndPlay(nva, lastTitle)
        } else {
            dismissError()
        }
    }

    /** 关闭错误状态并恢复空闲界面。 */
    private fun dismissError() {
        LogHub.i(TAG, "dismiss error; current=${current != null} castActive=$castActive")
        clearLiveSession("dismiss-error")
        if (::playerEngine.isInitialized) {
            stopActivePlayback("dismiss-error")
        } else {
            LogHub.w(TAG, "dismiss error without player engine")
        }
        current = null
        castActive = false
        resolving = false
        lastErrorRetry = null
        ui = ui.copy(
            mode = TvMode.IDLE,
            error = null,
            castTitle = "",
            qualityLabel = "",
            qualityDetail = "",
            qualitySource = "",
            audioLabel = "",
            isPlaying = false,
            isBuffering = false,
            isLive = false,
            liveFormat = "",
            liveCdn = "",
            liveBackupCount = 0,
            positionMs = 0,
            durationMs = 0,
            hudVisible = false,
            tracks = emptyList(),
            audios = emptyList(),
            showContinue = ui.rememberPlaybackContent,
        )
    }

    /** 停止当前电视播放并返回首页，保持应用与 DLNA 服务继续运行。 */
    private fun stopCasting() {
        LogHub.i(
            TAG,
            "stopCasting requested mode=${ui.mode} current=${current != null} " +
                "castActive=$castActive",
        )
        resolveJob?.cancel()
        resolveJob = null
        clearLiveSession("user-stop")
        pendingPause = false
        pendingSeekMs = 0L
        if (::playerEngine.isInitialized) {
            stopActivePlayback("user-stop")
        } else {
            LogHub.w(TAG, "stopCasting without player engine")
        }
        current = null
        castActive = false
        resolving = false
        lastNva = null
        lastTitle = ""
        lastErrorRetry = null
        runCatching { dlna.markCastFailed("user-stop") }
        PlaybackDiagnostics.closeSession("user-stop")
        LogHub.setCast("")
        LogHub.setPlay("")
        ui = ui.copy(
            mode = TvMode.IDLE,
            destination = TvDestination.HOME,
            settingsPage = SettingsPage.ROOT,
            sidebarVisible = true,
            panel = null,
            stopCastConfirm = false,
            error = null,
            castTitle = "",
            qualityLabel = "",
            qualityDetail = "",
            qualitySource = "",
            audioLabel = "",
            isPlaying = false,
            isBuffering = false,
            isLive = false,
            liveFormat = "",
            liveCdn = "",
            liveBackupCount = 0,
            positionMs = 0L,
            durationMs = 0L,
            hudVisible = false,
            tracks = emptyList(),
            audios = emptyList(),
            showContinue = ui.rememberPlaybackContent,
            toast = "投屏已停止",
        )
    }

    /** 展示错误信息并根据参数决定是否保留视频画面。 */
    private fun showError(
        title: String,
        message: String,
        code: String,
        canRetry: Boolean,
        keepVideo: Boolean = false,
    ) {
        val snapshot = if (::playerEngine.isInitialized) {
            runCatching { playerEngine.stateSnapshot() }.getOrDefault("(unavailable)")
        } else {
            "(player engine unavailable)"
        }
        LogHub.e(
            TAG,
            "showError code=$code canRetry=$canRetry keepVideo=$keepVideo " +
                "title=$title message=${LogRedaction.text(message)} snapshot=$snapshot",
        )
        PlaybackDiagnostics.attribute("ui.errorCode", code)
        PlaybackDiagnostics.event(
            "UiError",
            "code=$code canRetry=$canRetry keepVideo=$keepVideo title=$title",
        )
        if (!keepVideo) {
            clearLiveSession("show-error:$code")
            if (::playerEngine.isInitialized) {
                stopActivePlayback("show-error:$code")
            } else {
                LogHub.w(TAG, "showError cannot stop playback: player engine unavailable")
            }
            current = null
            castActive = false
        }
        runOnUiThread {
            ui = ui.copy(
                mode = TvMode.ERROR,
                error = TvError(title, message, code, canRetry),
                hudVisible = false,
                isPlaying = false,
                isBuffering = false,
                isLive = false,
                liveFormat = "",
                liveCdn = "",
                liveBackupCount = 0,
                panel = null,
                stopCastConfirm = false,
            )
        }
    }

    /** 首页连续两次返回退出，首按显示明确提示。 */
    private fun handleHomeBack() {
        finish()
    }

    /** 清除界面内的短暂状态提示。 */
    private fun clearToast() {
        ui = ui.copy(toast = null)
    }

    // ------------------------------------------------------------------ //
    // helpers
    // ------------------------------------------------------------------ //
    /** 读取当前后端的统一播放状态，供 DLNA、弹幕和界面轮询使用。 */
    private fun playerPositionMs(): Long = when (playbackBackend) {
        PlaybackBackend.MEDIA3 -> dolbyVisionPlayer?.positionMs() ?: 0L
        PlaybackBackend.VLC -> runCatching { player.time.coerceAtLeast(0L) }.getOrDefault(0L)
    }

    private fun playerDurationMs(): Long = when (playbackBackend) {
        PlaybackBackend.MEDIA3 -> dolbyVisionPlayer?.durationMs() ?: 0L
        PlaybackBackend.VLC -> runCatching { player.length.coerceAtLeast(0L) }.getOrDefault(0L)
    }

    private fun playerIsPlaying(): Boolean = when (playbackBackend) {
        PlaybackBackend.MEDIA3 -> dolbyVisionPlayer?.isPlaying() ?: false
        PlaybackBackend.VLC -> runCatching { player.isPlaying }.getOrDefault(false)
    }

    private fun playerVolume(): Int = when (playbackBackend) {
        PlaybackBackend.MEDIA3 -> dolbyVisionPlayer?.volume() ?: ui.volume
        PlaybackBackend.VLC -> runCatching { player.volume }.getOrDefault(ui.volume)
    }

    private fun seekPlayer(positionMs: Long, reason: String): Long = when (playbackBackend) {
        PlaybackBackend.MEDIA3 -> dolbyVisionPlayer?.seekTo(positionMs) ?: positionMs
        PlaybackBackend.VLC -> playerEngine.seekTo(positionMs, reason)
    }

    private fun setPlayerPaused(paused: Boolean, reason: String) {
        when (playbackBackend) {
            PlaybackBackend.MEDIA3 -> dolbyVisionPlayer?.setPaused(paused)
            PlaybackBackend.VLC -> playerEngine.setPaused(paused, reason)
        }
    }

    private fun setPlayerPlaybackRate(rate: Float, reason: String): Float = when (playbackBackend) {
        PlaybackBackend.MEDIA3 -> dolbyVisionPlayer?.setPlaybackRate(rate) ?: rate
        PlaybackBackend.VLC -> playerEngine.setPlaybackRate(rate, reason)
    }

    private fun setPlayerVolume(volume: Int, reason: String) {
        when (playbackBackend) {
            PlaybackBackend.MEDIA3 -> dolbyVisionPlayer?.setVolume(volume)
            PlaybackBackend.VLC -> playerEngine.setPlayerVolume(volume, reason)
        }
    }

    private fun stopActivePlayback(reason: String) {
        when (playbackBackend) {
            PlaybackBackend.MEDIA3 -> dolbyVisionPlayer?.stop()
            PlaybackBackend.VLC -> playerEngine.stopPlayback(reason)
        }
    }

    /** 启动定时任务以同步播放进度和设备网络信息。 */
    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            var tick = 0
            while (isActive) {
                delay(500)
                tick++
                if (::playerEngine.isInitialized &&
                    (ui.mode == TvMode.PLAYING || ui.mode == TvMode.LOADING)
                ) {
                    val pos = playerPositionMs()
                    val len = playerDurationMs()
                    val playing = playerIsPlaying()
                    val volume = playerVolume()
                    val live = current?.isLive == true
                    if (live) observeLiveProgress(playerEngine.liveProgressSnapshot(), playing)
                    ui = ui.copy(
                        positionMs = if (live) 0L else pos.coerceAtLeast(0L),
                        durationMs = if (live) 0L else if (len > 0) len else ui.durationMs,
                        isPlaying = playing,
                        volume = volume,
                        liveBackupCount = if (live) current?.liveBackupCount ?: 0 else 0,
                    )
                    if (playing && !ui.isBuffering) {
                        refreshVideoSize()
                        if (!live && ::danmakuController.isInitialized) {
                            danmakuController.onPlayerTime(pos.coerceAtLeast(0L))
                        }
                    }
                }
                if (tick % 10 == 0) {
                    ui = ui.copy(
                        network = deviceInfo.wifiName(),
                        ip = LogHub.localIp(),
                    )
                }
            }
        }
    }

    /** 检查直播时间线、网络读取或解码画面是否连续五秒没有前进。 */
    private fun observeLiveProgress(
        progress: VlcPlayerEngine.LiveProgressSnapshot,
        playing: Boolean,
    ) {
        val now = SystemClock.elapsedRealtime()
        val stalled = liveStallWatchdog.observe(
            positionMs = progress.positionMs,
            readBytes = progress.readBytes,
            demuxReadBytes = progress.demuxReadBytes,
            decodedVideoFrames = progress.decodedVideoFrames,
            displayedPictures = progress.displayedPictures,
            nowMs = now,
            playing = playing,
            buffering = ui.isBuffering,
        )
        if (stalled) {
            val detail = "time=${progress.positionMs}ms read=${progress.readBytes}B " +
                "demux=${progress.demuxReadBytes}B decoded=${progress.decodedVideoFrames} " +
                "displayed=${progress.displayedPictures}"
            LogHub.e(
                TAG,
                "live playback stalled for ${LIVE_STALL_TIMEOUT_MS}ms " +
                    "room=${liveSession?.stream?.roomId} $detail",
            )
            PlaybackDiagnostics.event(
                "LivePlaybackStalled",
                "room=${liveSession?.stream?.roomId} duration=${LIVE_STALL_TIMEOUT_MS}ms $detail",
            )
            handleLivePlaybackFailure("playback-time-stalled-5s")
        }
    }

    /** 限制播放器属性读取失败日志的频率。 */
    private fun logPlayerReadFailure(field: String, error: Throwable) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPlayerReadWarningAt < 10_000L) return
        lastPlayerReadWarningAt = now
        LogHub.w(TAG, "player read failed field=$field: ${LogRedaction.reason(error)}")
    }

    /** 将播放信息整理为诊断日志摘要。 */
    private fun describe(info: BiliApi.PlayInfo?): String {
        if (info == null) return ""
        val v = info.video
        val width = if (info.isLive && ui.videoWidth > 0) ui.videoWidth else v.width
        val height = if (info.isLive && ui.videoHeight > 0) ui.videoHeight else v.height
        val codec = v.codecs.ifBlank { VlcTrackFormat.codecName(v.codecid) }
        val audio = if (info.isLive) "自动音轨" else info.audioLabel
        return "${info.title}\n${info.qualityLabel}  ${width}x$height  " +
            "codec=$codec  $audio\n" +
            "aid=${info.aid} cid=${info.cid}  duration=${info.durationSec}s  " +
            "progressive=${info.progressiveUrl != null}  backup=${v.backup.size}\n" +
            "video=${LogRedaction.host(v.url)}  audio=${LogRedaction.host(info.audio?.url)}"
    }

    /** 恢复界面焦点并刷新设备网络信息。 */
    override fun onResume() {
        super.onResume()
        LogHub.i(TAG, "activity resume castActive=$castActive mode=${ui.mode}")
        if (!composeUi.hasFocus()) {
            composeUi.post { composeUi.requestFocus() }
        }
        ui = ui.copy(network = deviceInfo.wifiName(), ip = LogHub.localIp())
    }

    /** 暂停非投屏场景下的本地播放器。 */
    override fun onPause() {
        super.onPause()
        LogHub.i(TAG, "activity pause castActive=$castActive mode=${ui.mode}")
        if (!castActive && ::playerEngine.isInitialized) {
            if (playerIsPlaying()) {
                setPlayerPaused(true, "activity-pause")
                ui = ui.copy(isPlaying = false)
            }
        }
    }

    /** 释放投屏、登录、协程和播放器资源。 */
    override fun onDestroy() {
        super.onDestroy()
        LogHub.i(TAG, "activity destroy")
        clearLiveSession("activity-destroy")
        PlaybackDiagnostics.closeSession("activity-destroyed")
        runCatching { dlna.stop() }
        loginCoordinator.cancel()
        updateCoordinator.cancel()
        resolveJob?.cancel()
        tickerJob?.cancel()
        runCatching { if (::danmakuController.isInitialized) danmakuController.release() }
        scope.cancel()
        runCatching { dolbyVisionPlayer?.release() }
        runCatching { if (::playerEngine.isInitialized) playerEngine.release() }
        LogHub.stop()
    }

    companion object {
        /** 日志标签。 */
        private const val TAG = "SimpleBili"
        /** 首页双击返回退出计时窗口。 */
        private const val HOME_BACK_EXIT_WINDOW_MS = HomeBackExitTiming.EXIT_WINDOW_MS
        /** 直播备用线路评分刷新周期。 */
        private const val LIVE_BACKUP_REFRESH_INTERVAL_MS = 30_000L
        /** 直播播放位置无前进时触发故障切源的时间阈值。 */
        private const val LIVE_STALL_TIMEOUT_MS = 5_000L
        /** 是否已经安装全局崩溃记录器。 */
        @Volatile
        private var crashHandlerInstalled = false
    }

    /** 当前用于输出视频和音频的实际播放后端。 */
    private enum class PlaybackBackend {
        VLC,
        MEDIA3,
    }
}
