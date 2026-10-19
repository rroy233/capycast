package com.roy233.tvcast

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.roy233.tvcast.ui.model.PanelKind
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
import com.roy233.tvcast.core.diagnostics.LogHub
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

/**
 * 应用主界面及投屏生命周期控制器。
 *
 * 负责连接 VLC 播放器、DLNA 渲染器、账号登录协调器和 Compose 电视界面。
 */
class MainActivity : ComponentActivity() {

    /** VLC 播放引擎。 */
    private lateinit var playerEngine: VlcPlayerEngine
    /** VLC 播放器实例。 */
    private val player: MediaPlayer get() = playerEngine.player
    /** 视频渲染布局。 */
    private lateinit var videoLayout: VLCVideoLayout
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

    /** 电视界面状态。 */
    private var ui by mutableStateOf(TvUiState())
    /** 最近投屏记录存储。 */
    private val recentCastStore by lazy { RecentCastStore(this) }
    /** 首页双击返回退出计时。 */
    private val homeBackExitGate = DoubleBackExitGate(HOME_BACK_EXIT_WINDOW_MS)
    /** 设备身份偏好读取器。 */
    private val uiPreferences by lazy { UiPreferences(this) }
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
            runOnUiThread { handleCast(title, longDescription) }
        }

        /** 处理远端播放命令。 */
        override fun onPlay() {
            pendingPause = false
            runCatching { if (!player.isPlaying) player.play() }
            runOnUiThread { ui = ui.copy(isPlaying = true, hudVisible = true) }
        }

        /** 处理远端暂停命令。 */
        override fun onPause() {
            pendingPause = true
            runCatching { if (player.isPlaying) player.pause() }
            runOnUiThread { ui = ui.copy(isPlaying = false, hudVisible = true) }
        }

        /** 处理远端停止命令。 */
        override fun onStop() {
            pendingPause = false
            runCatching { player.stop() }
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
            runCatching { player.time = positionMs.coerceAtLeast(0L) }
            runOnUiThread {
                ui = ui.copy(
                    positionMs = positionMs.coerceAtLeast(0L),
                    hudVisible = true,
                )
            }
        }

        /** 处理远端音量设置命令。 */
        override fun onSetVolume(volume: Int) {
            val v = volume.coerceIn(0, 100)
            runCatching { player.volume = v }
            runOnUiThread { ui = ui.copy(volume = v, hudVisible = true) }
        }

        /** 返回播放器当前播放位置。 */
        override fun positionMs(): Long =
            runCatching { player.time.coerceAtLeast(0L) }.getOrDefault(0L)

        /** 返回播放器当前媒体时长。 */
        override fun durationMs(): Long =
            runCatching { player.length.coerceAtLeast(0L) }.getOrDefault(0L)

        /** 返回播放器当前是否正在播放。 */
        override fun isPlaying(): Boolean =
            runCatching { player.isPlaying }.getOrDefault(false)

        /** 返回播放器当前音量。 */
        override fun volume(): Int =
            runCatching { player.volume }.getOrDefault(50)
    }

    /** 初始化播放器、投屏服务、界面和账号状态。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                TvKeyHandler.dispatchBack(ui, actions)
            }
        })

        videoLayout = findViewById(R.id.videoLayout)
        composeUi = findViewById(R.id.composeUi)
        composeUi.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        composeUi.setContent {
            TvRoot(state = ui, actions = actions)
        }
        composeUi.isFocusable = true
        composeUi.post { composeUi.requestFocus() }

        LogHub.start(this)
        dlna = DlnaRenderer(this, dlnaListener, deviceName = castDeviceName)
        dlna.start()

        ui = ui.copy(
            deviceName = castDeviceName,
            roomName = uiPreferences.roomName,
            network = deviceInfo.wifiName(),
            ip = LogHub.localIp(),
            version = deviceInfo.versionName(),
            dlnaUrl = dlna.descriptionUrl,
        )
        updateCoordinator.initialize()

        playerEngine = VlcPlayerEngine(this, videoLayout) { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    LogHub.i(TAG, "vlc playing")
                    val seek = pendingSeekMs
                    pendingSeekMs = 0L
                    runOnUiThread {
                        ui = ui.copy(
                            mode = TvMode.PLAYING,
                            isPlaying = true,
                            isBuffering = false,
                            positionMs = if (seek > 0) seek else ui.positionMs,
                        )
                        if (seek > 0) {
                            runCatching { player.time = seek }
                        }
                        val duration = runCatching { player.length }.getOrDefault(0L)
                        if (duration > 0) {
                            ui = ui.copy(durationMs = duration)
                        }
                    }
                }

                MediaPlayer.Event.Paused -> {
                    runOnUiThread { ui = ui.copy(isPlaying = false) }
                }

                MediaPlayer.Event.Stopped -> {
                    runOnUiThread { ui = ui.copy(isPlaying = false, isBuffering = false) }
                }

                MediaPlayer.Event.EndReached -> {
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
                            toast = "播放结束",
                            showContinue = ui.rememberPlaybackContent,
                        )
                    }
                }

                MediaPlayer.Event.EncounteredError -> {
                    LogHub.e(TAG, "vlc encountered error")
                    val info = current
                    if (info != null) {
                        lastErrorRetry = {
                            play(info, runCatching { player.time }.getOrDefault(0L))
                        }
                    }
                    showError(
                        title = "播放失败",
                        message = "设备解码能力不足或网络异常。",
                        code = "PLAYBACK_FAILED",
                        canRetry = info != null,
                        keepVideo = true,
                    )
                }

                MediaPlayer.Event.Buffering -> {
                    val buffering = event.buffering < 100f
                    runOnUiThread { ui = ui.copy(isBuffering = buffering) }
                }

                else -> {}
            }
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
        if (resolving) return
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
    private fun handleCast(title: String, longDescription: String) {
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
            dlna.markCastFailed()
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
        LogHub.i(
            TAG,
            "cast nva aid=${nva.aid} cid=${nva.cid} bvid=${nva.bvid} " +
                "eid=${nva.eid} sid=${nva.sid} room=${nva.roomId}"
        )
        resolveNvaAndPlay(nva, title)
    }

    /** 根据 nva 内容解析播放地址并开始播放。 */
    private fun resolveNvaAndPlay(nva: NvaExtDecoder.NvaExtContent, title: String) {
        resolveJob?.cancel()
        resolving = true
        lastNva = nva
        lastTitle = title
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
                        "codec=${info.video.codecid} audio=${info.audio?.id}"
                )
                dlna.setDurationMs(info.durationSec * 1000)
                play(info, 0L)
            }.onFailure { t ->
                LogHub.e(TAG, "cast resolve failed: ${t.message}", t)
                dlna.markCastFailed()
                val message = t.message.orEmpty()
                val live = message.contains("live", ignoreCase = true)
                val pgc = message.contains("PGC season", ignoreCase = true)
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
                    code = when {
                        live -> "LIVE_UNSUPPORTED"
                        pgc -> "PGC_UNSUPPORTED"
                        else -> "RESOLVE_FAILED"
                    },
                    canRetry = !live && !pgc,
                )
            }
        }
    }

    /** 使用指定播放信息打开播放器并更新界面状态。 */
    private fun play(info: BiliApi.PlayInfo, seekToMs: Long) {
        current = info
        castActive = true
        lastTitle = info.title
        lastErrorRetry = null
        pendingSeekMs = seekToMs.coerceAtLeast(0L)

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

        playerEngine.open(info, seekToMs)

        if (pendingPause) {
            pendingPause = false
            runCatching { player.pause() }
            ui = ui.copy(isPlaying = false)
        }
        LogHub.setPlay(describe(info))
        LogHub.i(TAG, "play qn=${info.video.id} ${info.video.width}x${info.video.height}")
    }

    /** 切换当前视频清晰度并从当前位置继续播放。 */
    private fun switchQuality(qn: Int) {
        val info = current ?: return
        val track = info.videos.firstOrNull { it.id == qn } ?: return
        val position = runCatching { player.time }.getOrDefault(ui.positionMs)
        play(info.copy(video = track), position)
        ui = ui.copy(
            panel = PanelKind.QUALITY,
            qualitySource = "手动",
            toast = "已切换 ${BiliApi.qnLabel(qn)}",
        )
    }

    /** 切换当前音轨并从当前位置继续播放。 */
    private fun switchAudio(id: Int) {
        val info = current ?: return
        val track = info.audios.firstOrNull { it.id == id } ?: return
        val position = runCatching { player.time }.getOrDefault(ui.positionMs)
        play(info.copy(audio = track), position)
        ui = ui.copy(
            panel = PanelKind.AUDIO,
            toast = "已切换 ${BiliApi.audioLabelFor(id)}",
        )
    }

    /** 切换播放器的播放与暂停状态。 */
    private fun togglePlayPause() {
        if (!::playerEngine.isInitialized) return
        if (ui.mode != TvMode.PLAYING && ui.mode != TvMode.LOADING) return
        runCatching {
            if (player.isPlaying) player.pause() else player.play()
        }
        ui = ui.copy(
            isPlaying = runCatching { player.isPlaying }.getOrDefault(ui.isPlaying),
            hudVisible = true,
        )
    }

    /** 按指定毫秒数调整当前播放位置。 */
    private fun seekBy(ms: Long) {
        if (!::playerEngine.isInitialized) return
        val length = runCatching { player.length }.getOrDefault(0L)
        val max = if (length > 0) length else Long.MAX_VALUE
        val target = (runCatching { player.time }.getOrDefault(ui.positionMs) + ms)
            .coerceIn(0L, max)
        runCatching { player.time = target }
        ui = ui.copy(positionMs = target, hudVisible = true)
    }

    /** 设置播放器音量并同步界面状态。 */
    private fun setVolume(volume: Int) {
        if (!::playerEngine.isInitialized) return
        val v = volume.coerceIn(0, 100)
        runCatching { player.volume = v }
        ui = ui.copy(volume = v, hudVisible = true)
    }

    /** 重试最近一次失败的解析或播放操作。 */
    private fun retryLastAction() {
        val action = lastErrorRetry
        lastErrorRetry = null
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
        runCatching { if (::playerEngine.isInitialized) player.stop() }
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
        resolveJob?.cancel()
        resolveJob = null
        pendingPause = false
        pendingSeekMs = 0L
        runCatching { if (::playerEngine.isInitialized) player.stop() }
        current = null
        castActive = false
        resolving = false
        lastNva = null
        lastTitle = ""
        lastErrorRetry = null
        runCatching { dlna.markCastFailed() }
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
        if (!keepVideo) {
            runCatching { if (::playerEngine.isInitialized) player.stop() }
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
                    val pos = runCatching { player.time }.getOrDefault(ui.positionMs)
                    val len = runCatching { player.length }.getOrDefault(0L)
                    val playing = runCatching { player.isPlaying }.getOrDefault(ui.isPlaying)
                    val volume = runCatching { player.volume }.getOrDefault(ui.volume)
                    ui = ui.copy(
                        positionMs = pos.coerceAtLeast(0L),
                        durationMs = if (len > 0) len else ui.durationMs,
                        isPlaying = playing,
                        volume = volume,
                    )
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

    /** 将播放信息整理为诊断日志摘要。 */
    private fun describe(info: BiliApi.PlayInfo?): String {
        if (info == null) return ""
        val v = info.video
        return "${info.title}\n${info.qualityLabel}  ${v.width}x${v.height}  " +
            "codec=${v.codecid}  ${info.audioLabel}\n" +
            "aid=${info.aid} cid=${info.cid}  backup=${v.backup.size}"
    }

    /** 恢复界面焦点并刷新设备网络信息。 */
    override fun onResume() {
        super.onResume()
        if (!composeUi.hasFocus()) {
            composeUi.post { composeUi.requestFocus() }
        }
        ui = ui.copy(network = deviceInfo.wifiName(), ip = LogHub.localIp())
    }

    /** 暂停非投屏场景下的本地播放器。 */
    override fun onPause() {
        super.onPause()
        if (!castActive && ::playerEngine.isInitialized && player.isPlaying) {
            player.pause()
            ui = ui.copy(isPlaying = false)
        }
    }

    /** 释放投屏、登录、协程和播放器资源。 */
    override fun onDestroy() {
        super.onDestroy()
        runCatching { dlna.stop() }
        loginCoordinator.cancel()
        updateCoordinator.cancel()
        resolveJob?.cancel()
        tickerJob?.cancel()
        scope.cancel()
        LogHub.stop()
        runCatching { if (::playerEngine.isInitialized) playerEngine.release() }
    }

    companion object {
        /** 日志标签。 */
        private const val TAG = "SimpleBili"
        private const val HOME_BACK_EXIT_WINDOW_MS = HomeBackExitTiming.EXIT_WINDOW_MS
    }
}
