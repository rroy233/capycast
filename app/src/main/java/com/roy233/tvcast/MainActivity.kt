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

    /** 电视界面状态。 */
    private var ui by mutableStateOf(TvUiState())
    /** 最近投屏记录存储。 */
    private val recentCastStore by lazy { RecentCastStore(this) }
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
            authTokenStore = AuthTokenStore(this),
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
            val enabled = !danmakuSettings.enabled
            updateDanmakuSettings(danmakuSettings.copy(enabled = enabled))
            ui = ui.copy(toast = if (enabled) "弹幕已开启" else "弹幕已关闭")
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
            ui = ui.copy(
                panel = kind,
                hudVisible = true,
                stopCastConfirm = false,
            )
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
            if (::playerEngine.isInitialized) {
                stopActivePlayback("dlna-stop")
            } else {
                LogHub.w(TAG, "dlna stop ignored: player engine unavailable")
            }
            castActive = false
            current = null
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
            playerPositionMs()

        /** 返回播放器当前媒体时长。 */
        override fun durationMs(): Long =
            playerDurationMs()

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
                        nva.hasUgc ->
                            BiliApi.resolveByAidCid(nva.aid, nva.cid, title, nva.bvid ?: "")
                        nva.hasPgc && nva.eid > 0 ->
                            BiliApi.resolvePgc(nva.eid, nva.cid, title)
                        nva.hasPgc ->
                            error("PGC season without epId is not supported")
                        nva.hasLive ->
                            error("live casting is not supported")
                        else ->
                            error("no playable identity in nva_ext")
                    }
                }
            }
            if (!isActive) return@launch
            resolving = false
            result.onSuccess { info ->
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
            }.onFailure { t ->
                val message = t.message.orEmpty()
                val live = message.contains("live", ignoreCase = true)
                val pgc = message.contains("PGC season", ignoreCase = true)
                val code = when {
                    live -> "LIVE_UNSUPPORTED"
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
                if (!live && !pgc) {
                    lastErrorRetry = { resolveNvaAndPlay(nva, title) }
                }
                showError(
                    title = when {
                        live -> "直播投屏暂未支持"
                        pgc -> "该内容暂不支持"
                        else -> "取流失败"
                    },
                    message = when {
                        live -> "当前版本先支持点播。"
                        pgc -> "请在手机上选择具体剧集后重试。"
                        else -> "请检查网络或账号权益。"
                    },
                    code = code,
                    canRetry = !live && !pgc,
                )
            }
        }
    }

    /** 使用指定播放信息打开播放器并更新界面状态。 */
    private fun play(info: BiliApi.PlayInfo, seekToMs: Long) {
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
            return
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
            qualityLabel = info.qualityLabel,
            qualityDetail = info.qualityDetail,
            qualitySource = if (info.video.id >= 80) "已提升" else "",
            audioLabel = info.audioLabel,
            aid = info.aid,
            cid = info.cid,
            isPlaying = true,
            isBuffering = false,
            positionMs = seekToMs.coerceAtLeast(0L),
            durationMs = info.durationSec * 1000,
            selectedQn = info.video.id,
            selectedAudioId = info.audio?.id ?: 0,
            tracks = TrackOptionFactory.tracks(info),
            audios = TrackOptionFactory.audios(info),
            hudVisible = true,
            panel = null,
            error = null,
            stopCastConfirm = false,
        )
        addRecent(info)

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
            lastErrorRetry = { play(info, seekToMs) }
            showError(
                title = "播放失败",
                message = "无法打开该视频。诊断日志已记录具体原因。",
                code = "PLAYER_OPEN_FAILED",
                canRetry = true,
                keepVideo = false,
            )
            return
        }

        if (::danmakuController.isInitialized) {
            danmakuController.open(info.aid, info.cid, info.durationSec * 1000, seekToMs)
            danmakuController.onPlaybackRateChanged(ui.playbackSpeed)
            danmakuController.applySettings(danmakuSettings)
            layoutDanmaku()
        }
        setPlayerPlaybackRate(ui.playbackSpeed, "play-restore-rate")

        if (pendingPause) {
            pendingPause = false
            setPlayerPaused(true, "pending-pause")
            ui = ui.copy(isPlaying = false)
        }
        LogHub.setPlay(describe(info))
        LogHub.i(TAG, "play opened qn=${info.video.id} ${info.video.width}x${info.video.height}")
    }

    /** 切换当前视频清晰度并从当前位置继续播放。 */
    private fun switchQuality(qn: Int) {
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

    /** 切换当前音轨并从当前位置继续播放。 */
    private fun switchAudio(id: Int) {
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
        if (ui.seekPreviewPositionMs != null) {
            ui = ui.copy(seekPreviewPositionMs = null)
        }
    }

    /** 将 VLC 与弹幕时钟切换到相同的标准播放速度。 */
    private fun setPlaybackSpeed(rate: Float) {
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
        if (size.first == videoWidth && size.second == videoHeight) return
        videoWidth = size.first
        videoHeight = size.second
        ui = ui.copy(videoWidth = videoWidth, videoHeight = videoHeight)
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
                    ui = ui.copy(
                        positionMs = pos.coerceAtLeast(0L),
                        durationMs = if (len > 0) len else ui.durationMs,
                        isPlaying = playing,
                        volume = volume,
                    )
                    if (playing && !ui.isBuffering) {
                        refreshVideoSize()
                        if (::danmakuController.isInitialized) {
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
        return "${info.title}\n${info.qualityLabel}  ${v.width}x${v.height}  " +
            "codec=${v.codecid}  ${info.audioLabel}\n" +
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
