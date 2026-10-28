package com.roy233.tvcast

import android.content.Context
import com.roy233.tvcast.data.auth.CastAuth
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.content.pm.ActivityInfo
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.roy233.tvcast.ui.model.PanelKind
import com.roy233.tvcast.ui.model.PlaybackSpeed
import com.roy233.tvcast.feature.cast.domain.CastContent
import com.roy233.tvcast.feature.cast.domain.RecentCast
import com.roy233.tvcast.ui.model.SettingsPage
import com.roy233.tvcast.ui.model.TvDestination
import com.roy233.tvcast.ui.model.TvError
import com.roy233.tvcast.ui.model.TvMode
import com.roy233.tvcast.ui.model.TvUiState
import com.roy233.tvcast.ui.model.CastPlaybackStateProjector
import com.roy233.tvcast.ui.tv.TvRoot
import com.roy233.tvcast.app.CapyCastContainer
import com.roy233.tvcast.app.PlaybackEngineHost
import com.roy233.tvcast.app.DlnaCastListenerAdapter
import com.roy233.tvcast.feature.cast.application.DlnaTransportCoordinator
import com.roy233.tvcast.feature.cast.application.DlnaTransportHost
import com.roy233.tvcast.ui.tv.TvActionDispatcher
import com.roy233.tvcast.ui.tv.TvActionHost
import com.roy233.tvcast.ui.tv.TvStateHolder
import com.roy233.tvcast.ui.tv.DoubleBackExitGate
import com.roy233.tvcast.ui.tv.HomeBackExitTiming
import com.roy233.tvcast.ui.tv.TvKeyHandler
import com.roy233.tvcast.data.bili.routing.OkHttpLiveRouteProbe
import com.roy233.tvcast.data.bili.routing.OkHttpVodRouteProbe
import com.roy233.tvcast.feature.playback.model.LivePlayInfo
import com.roy233.tvcast.feature.playback.model.LiveStreamCandidate
import com.roy233.tvcast.feature.playback.model.RankedLiveSource
import com.roy233.tvcast.feature.playback.model.RankedVodRoute
import com.roy233.tvcast.ui.model.LiveQualityOption
import com.roy233.tvcast.data.bili.NvaExtDecoder
import com.roy233.tvcast.core.playback.PlaybackBackend
import com.roy233.tvcast.core.playback.PlaybackEngine
import com.roy233.tvcast.core.playback.PlaybackEvent
import com.roy233.tvcast.core.playback.LivePlaybackTelemetry
import com.roy233.tvcast.core.playback.PlaybackSnapshot
import com.roy233.tvcast.core.playback.PlaybackRequest
import com.roy233.tvcast.feature.playback.application.PlaybackCoordinator
import com.roy233.tvcast.feature.playback.application.PlaybackSelectionCoordinator
import com.roy233.tvcast.feature.playback.application.PlaybackSelectionHost
import com.roy233.tvcast.feature.playback.application.PlaybackSessionHost
import com.roy233.tvcast.feature.playback.application.PlaybackSessionLifecycle
import com.roy233.tvcast.feature.playback.application.VodPlaybackCoordinator
import com.roy233.tvcast.feature.playback.application.LivePlaybackCoordinator
import com.roy233.tvcast.feature.playback.application.LivePlaybackHost
import com.roy233.tvcast.feature.playback.application.CastSessionCoordinator
import com.roy233.tvcast.feature.playback.application.CastSessionHost
import com.roy233.tvcast.feature.playback.application.PlaybackTicker
import com.roy233.tvcast.feature.playback.application.PlaybackTickerHost
import com.roy233.tvcast.feature.playback.application.PlaybackEventHost
import com.roy233.tvcast.feature.playback.application.PlaybackEventRouter
import com.roy233.tvcast.feature.playback.application.VodPlaybackHost
import com.roy233.tvcast.feature.playback.domain.VodPlaybackRules
import com.roy233.tvcast.feature.playback.routing.CdnRouteRules
import com.roy233.tvcast.feature.playback.model.CastResolution
import com.roy233.tvcast.feature.playback.model.LiveSession
import com.roy233.tvcast.feature.playback.model.PendingLiveRouteSwitch
import com.roy233.tvcast.feature.playback.model.VodSession
import com.roy233.tvcast.feature.playback.presentation.LiveQualityMapper
import com.roy233.tvcast.core.diagnostics.DeviceDiagnostics
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction
import com.roy233.tvcast.core.diagnostics.PlaybackDiagnostics
import com.roy233.tvcast.core.danmaku.AiShieldLevel
import com.roy233.tvcast.core.danmaku.DanmakuController
import com.roy233.tvcast.core.danmaku.DanmakuSettings
import com.roy233.tvcast.core.danmaku.DfmDanmakuEngine
import com.roy233.tvcast.core.danmaku.LiveDanmakuController
import com.roy233.tvcast.core.danmaku.MediaClock
import com.roy233.tvcast.data.bili.danmaku.DanmakuApi
import com.roy233.tvcast.data.bili.danmaku.DanmakuRepository
import com.roy233.tvcast.feature.history.PlaybackHistoryContent
import com.roy233.tvcast.feature.update.model.UpdateChannel
import com.roy233.tvcast.feature.account.AccountUiState
import com.roy233.tvcast.platform.dlna.DlnaRenderer
import com.roy233.tvcast.feature.playback.presentation.TrackOptionMapper
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
import com.roy233.tvcast.platform.player.VlcTrackFormat
import master.flame.danmaku.ui.widget.DanmakuView

/**
 * 应用主界面及投屏生命周期控制器。
 *
 * 负责连接 VLC 播放器、DLNA 渲染器、账号登录协调器和 Compose 电视界面。
 */
@UnstableApi
class MainActivity : ComponentActivity(), TvActionHost, VodPlaybackHost, LivePlaybackHost, PlaybackTickerHost, CastSessionHost, PlaybackEventHost, PlaybackSelectionHost, PlaybackSessionHost, DlnaTransportHost {

    /** 当前播放后端及其原生资源。 */
    private lateinit var playbackEngineHost: PlaybackEngineHost
    /** 统一接收播放后端转换后的事件。 */
    private val playbackEventRouter = PlaybackEventRouter(this)

    /** 视频渲染布局。 */
    private lateinit var videoLayout: VLCVideoLayout
    /** Media3 使用的 SurfaceView 容器，仅杜比视界会显示。 */
    private lateinit var media3PlayerView: PlayerView
    /** 弹幕渲染视图。 */
    private lateinit var danmakuView: DanmakuView
    /** 弹幕渲染引擎，点播与直播共用。 */
    private lateinit var danmakuEngine: DfmDanmakuEngine
    /** 弹幕会话控制器。 */
    private lateinit var danmakuController: DanmakuController
    /** 直播弹幕会话控制器。 */
    private lateinit var liveDanmakuController: LiveDanmakuController
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

    /** 当前播放器使用的稳定播放请求。 */
    private var currentRequest: PlaybackRequest? = null
    /** 当前是否存在活动投屏。 */
    private var castActive = false
    /** 当前是否正在解析媒体信息。 */
    private var resolving = false
    /** 是否在播放器准备完成后保持暂停。 */
    private var pendingPause = false
    /** 待应用的播放起始位置，单位为毫秒。 */
    private var pendingSeekMs = 0L
    /** 最近一次解析得到的 nva 内容。 */
    private var lastNva: CastContent? = null
    /** 最近一次投屏标题。 */
    private var lastTitle = ""
    /** 最近一次播放器读取失败告警的单调时刻。 */
    private var lastPlayerReadWarningAt = 0L
    /** 点播会话协调器。 */
    private val vodCoordinator by lazy { VodPlaybackCoordinator(this, OkHttpVodRouteProbe()) }
    /** 直播会话协调器。 */
    private val liveCoordinator by lazy { LivePlaybackCoordinator(this, OkHttpLiveRouteProbe()) }
    /** 播放轮询协调器。 */
    private val playbackTicker by lazy { PlaybackTicker(this) }
    /** 投屏会话协调器。 */
    private val castCoordinator by lazy { CastSessionCoordinator(this) }
    private val playbackSelectionCoordinator by lazy { PlaybackSelectionCoordinator(this) }
    private val playbackSessionLifecycle by lazy { PlaybackSessionLifecycle(this) }

    /** 电视界面根状态持有者。 */
    private val stateHolder = TvStateHolder()

    /** 电视界面状态；读取即订阅 Compose 快照，写入即触发重组。 */
    private var ui: TvUiState
        get() = stateHolder.read()
        set(value) = stateHolder.set(value)

    /** 应用依赖组合根。 */
    private val container by lazy {
        CapyCastContainer(
            context = this,
            scope = scope,
            readAccountState = {
                AccountUiState(
                    loggedIn = ui.loggedIn,
                    accountName = ui.accountName,
                    mid = ui.mid,
                    vip = ui.vip,
                    loginQr = ui.loginQr,
                    loginStatus = ui.loginStatus,
                    loginSkipped = ui.loginSkipped,
                )
            },
            writeAccountState = { account ->
                ui = ui.copy(
                    loggedIn = account.loggedIn,
                    accountName = account.accountName,
                    mid = account.mid,
                    vip = account.vip,
                    loginQr = account.loginQr,
                    loginStatus = account.loginStatus,
                    loginSkipped = account.loginSkipped,
                )
            },
            readUpdateState = { ui.update },
            writeUpdateState = { update -> ui = ui.copy(update = update) },
            onLogoutUi = {
                ui = ui.copy(
                    destination = TvDestination.HOME,
                    sidebarVisible = true,
                    showContinue = ui.rememberPlaybackContent,
                )
            },
            onToast = { message -> ui = ui.copy(toast = message) },
            openUpdateSettingsNavigation = {
                ui = ui.copy(
                    destination = TvDestination.SETTINGS,
                    settingsPage = SettingsPage.UPDATE,
                    sidebarVisible = false,
                    hudVisible = false,
                )
            },
            closeUpdateSettingsNavigation = {
                ui = ui.copy(settingsPage = SettingsPage.ROOT)
            },
            openUpdateNotesNavigation = {
                ui = ui.copy(settingsPage = SettingsPage.UPDATE_NOTES)
            },
            closeUpdateNotesNavigation = {
                ui = ui.copy(settingsPage = SettingsPage.UPDATE)
            },
            isPlaying = { playerIsPlaying() },
            positionMs = { playerPositionMs() },
            durationMs = { playerDurationMs() },
        )
    }

    /** 最近投屏记录存储。 */
    private val recentCastStore get() = container.recentCastStore
    /** TV 登录令牌存储；直播协议需要其中的 access_key。 */
    private val authTokenStore get() = container.authTokenStore
    /** 将投屏进度与直播心跳同步到 B 站账号。 */
    private val playbackHistoryCoordinator get() = container.playbackHistoryCoordinator
    /** 首页双击返回退出计时。 */
    private val homeBackExitGate = DoubleBackExitGate(HOME_BACK_EXIT_WINDOW_MS)
    /** 设备身份偏好读取器。 */
    private val uiPreferences get() = container.uiPreferences
    /** 弹幕设置读取器。 */
    private val danmakuPreferences get() = container.danmakuPreferences
    /** 设备信息读取器。 */
    private val deviceInfo get() = container.deviceInfo
    /** 对外显示并广播的投屏设备名称。 */
    private val castDeviceName get() = container.castDeviceName
    /** 账号二维码登录协调器。 */
    private val loginCoordinator get() = container.loginCoordinator
    /** 应用更新流程协调器。 */
    private val updateCoordinator get() = container.updateCoordinator
    private val playbackResolver get() = container.playbackResolver
    private val livePlaybackResolver get() = container.livePlaybackResolver

    /** 通用播放控制协调器。 */
    private val playbackCoordinator by lazy {
        PlaybackCoordinator(
            canControl = { ui.mode == TvMode.PLAYING || ui.mode == TvMode.LOADING },
            canPreviewSeek = { ui.mode == TvMode.PLAYING },
            displayedDurationMs = { ui.durationMs },
            previewPositionMs = { ui.seekPreviewPositionMs },
            updatePlaying = { playing -> ui = ui.copy(isPlaying = playing, hudVisible = true) },
            updatePosition = { position, duration ->
                ui = ui.copy(positionMs = position, durationMs = duration ?: ui.durationMs, hudVisible = true)
            },
            updatePreview = { position, duration ->
                ui = ui.copy(
                    positionMs = position ?: ui.positionMs,
                    durationMs = duration ?: ui.durationMs,
                    seekPreviewPositionMs = position,
                )
            },
            updateSpeed = { speed, message ->
                ui = ui.copy(playbackSpeed = speed, hudVisible = true, toast = message)
            },
            updateVolume = { volume -> ui = ui.copy(volume = volume, hudVisible = true) },
            showToast = { message -> ui = ui.copy(toast = message) },
            isEngineInitialized = { ::playbackEngineHost.isInitialized },
            isLive = { currentRequest?.isLive == true },
            positionMs = { playerPositionMs() },
            durationMs = { playerDurationMs() },
            isPlaying = { playerIsPlaying() },
            seekPlayer = { position, reason -> seekPlayer(position, reason) },
            setPlayerPaused = { paused, reason -> setPlayerPaused(paused, reason) },
            setPlayerPlaybackRate = { rate, reason -> setPlayerPlaybackRate(rate, reason) },
            setPlayerVolume = { volume, reason -> setPlayerVolume(volume, reason) },
            onDanmakuSeek = { position ->
                if (::danmakuController.isInitialized) danmakuController.onSeek(position)
            },
            onDanmakuPlaybackRateChanged = { rate ->
                if (::danmakuController.isInitialized) danmakuController.onPlaybackRateChanged(rate)
            },
        )
    }

    /** 电视界面操作装配器。 */
    private val actionDispatcher = TvActionDispatcher(stateHolder, this)

    /** 提供给电视界面的操作回调集合。 */
    private val actions = actionDispatcher.actions

    override fun requestLogin() = startQrLogin()

    override fun requestSkipLogin() = skipLogin()

    override fun requestLogout() = logout()

    override fun openUpdateSettings() = updateCoordinator.openSettings()

    override fun closeUpdateSettings() = updateCoordinator.closeSettings()

    override fun openUpdateNotes() = updateCoordinator.openNotes()

    override fun closeUpdateNotes() = updateCoordinator.closeNotes()

    override fun selectUpdateChannel(channel: UpdateChannel) =
        updateCoordinator.selectChannel(channel)

    override fun setAutomaticUpdateCheck(enabled: Boolean) =
        updateCoordinator.setAutomaticCheck(enabled)

    override fun checkForUpdates() = updateCoordinator.checkForUpdates()

    override fun downloadUpdate() = updateCoordinator.downloadUpdate()

    override fun cancelUpdateDownload() = updateCoordinator.cancelUpdateDownload()

    override fun installUpdate() = updateCoordinator.installUpdate()

    override fun requestPlayPause() = togglePlayPause()

    override fun requestSeekBy(deltaMs: Long) = seekBy(deltaMs)

    override fun requestPreviewSeekBy(deltaMs: Long) = previewSeekBy(deltaMs)

    override fun requestConfirmSeek() = confirmPreviewSeek()

    override fun requestPlaybackSpeed(speed: Float) = setPlaybackSpeed(speed)

    override fun toggleDanmaku() {
        val enabled = !danmakuSettings.enabled
        updateDanmakuSettings(danmakuSettings.copy(enabled = enabled))
        ui = ui.copy(toast = if (enabled) "弹幕已开启" else "弹幕已关闭")
    }

    override fun setDanmakuAiShield(level: AiShieldLevel) =
        updateDanmakuSettings(danmakuSettings.copy(aiShield = level))

    override fun setDanmakuArea(ratio: Float) =
        updateDanmakuSettings(danmakuSettings.copy(areaRatio = ratio))

    override fun setDanmakuMaxOnScreen(max: Int) =
        updateDanmakuSettings(danmakuSettings.copy(maxOnScreen = max))

    override fun setDanmakuOpacity(opacity: Float) =
        updateDanmakuSettings(danmakuSettings.copy(opacity = opacity))

    override fun setDanmakuFontScale(scale: Float) =
        updateDanmakuSettings(danmakuSettings.copy(fontScale = scale))

    override fun setDanmakuDuration(seconds: Float) =
        updateDanmakuSettings(danmakuSettings.copy(durationSec = seconds))

    override fun requestVolume(volume: Int) = setVolume(volume)

    override fun selectQuality(qn: Int) = switchQuality(qn)

    override fun selectAudio(id: Int) = switchAudio(id)

    override fun requestRetry() = retryLastAction()

    override fun requestDismissError() = dismissError()

    override fun requestConfirmStopCast() = stopCasting()

    override fun requestClearToast() = clearToast()

    override fun requestRecast(target: RecentCast) = recast(target)

    override fun setRememberPlaybackContent(enabled: Boolean) {
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
    }

    override fun setSyncPlaybackHistory(enabled: Boolean) {
        uiPreferences.syncPlaybackHistory = enabled
        ui = ui.copy(syncPlaybackHistory = enabled)
        playbackHistoryCoordinator.onEnabledChanged(
            enabled = enabled,
            accountMid = authTokenStore.load()?.mid ?: ui.mid,
        )
    }

    override fun handleHomeBack() = handleHomeBackInternal()

    override fun remembersPlaybackContent(): Boolean = ui.rememberPlaybackContent
    override fun playbackSpeed(): Float = ui.playbackSpeed
    override fun showRecentLoading(title: String) {
        ui = CastPlaybackStateProjector.recentLoading(ui, title)
    }
    override fun showIncomingCast(title: String) {
        ui = CastPlaybackStateProjector.incomingCast(ui, title)
    }
    override fun showResolveLoading(title: String) {
        ui = CastPlaybackStateProjector.resolving(ui, title)
    }
    override fun showPlayback(request: PlaybackRequest, seekToMs: Long) {
        val liveQualities = if (request.isLive) {
            liveCoordinator.activeSession?.let { LiveQualityMapper.options(it.stream) } ?: emptyList()
        } else emptyList()
        ui = CastPlaybackStateProjector.playing(ui, request, seekToMs, liveQualities)
    }
    override fun setDesiredPlaybackSpeed(speed: Float) {
        ui = ui.copy(playbackSpeed = speed)
    }

    override fun vodCoordinator(): VodPlaybackCoordinator = vodCoordinator
    override fun liveCoordinator(): LivePlaybackCoordinator = liveCoordinator
    override fun openSelectedPlayback(request: PlaybackRequest, positionMs: Long): Boolean =
        castCoordinator.openPlayback(request, positionMs)
    override fun liveAccessKey(): String? = authTokenStore.load()?.accessKey
    override suspend fun resolveLiveQuality(
        accessKey: String, roomId: Long, quality: Int, forceAvc: Boolean,
    ): LivePlayInfo = livePlaybackResolver.resolve(accessKey, roomId, quality, forceAvc)
    override fun switchSelectedLiveRoute(
        target: RankedLiveSource, reason: String, from: LiveStreamCandidate?,
    ): Boolean = switchLiveRoute(target, reason, from)
    override suspend fun onSelectedLiveRouteOpenFailure(reason: String) {
        handleLiveRouteOpenFailure(reason)
    }
    override fun onQualitySelected(label: String) {
        ui = ui.copy(panel = PanelKind.QUALITY, qualitySource = "手动", toast = "已切换 $label")
    }
    override fun onAudioSelected(label: String) {
        ui = ui.copy(panel = PanelKind.AUDIO, toast = "已切换 $label")
    }
    override fun showSelectionToast(message: String) { ui = ui.copy(toast = message) }

    override fun showRouteToast(message: String) { ui = ui.copy(toast = message) }
    override fun isPausedWithoutBuffering(): Boolean = !ui.isBuffering && !ui.isPlaying
    override fun updateBackupCount(count: Int) { ui = ui.copy(liveBackupCount = count) }

    override fun currentRequest(): PlaybackRequest? = currentRequest

    override fun setCurrent(request: PlaybackRequest) {
        currentRequest = request
    }

    /** 当前是否存在活动投屏。 */
    override fun isCastActive(): Boolean = castActive

    /** 当前播放位置，单位为毫秒。 */
    override fun positionMs(): Long = playerPositionMs()

    override fun openPlayback(request: PlaybackRequest, seekToMs: Long): Boolean {
        currentRequest = request
        return runCatching { playbackEngineHost.open(request, seekToMs) }
            .onFailure { error -> LogHub.e(TAG, "open playback backend failed ${LogRedaction.reason(error)}", error) }
            .getOrDefault(false)
    }

    /** 用于后台线路探测的协程作用域。 */
    override val coroutineScope: CoroutineScope get() = scope

    /** 播放器是否正在播放。 */
    override fun isPlaying(): Boolean = playerIsPlaying()

    /** VLC 播放器的失败快照，用于直播故障诊断。 */
    override fun stateSnapshot(): String =
        if (::playbackEngineHost.isInitialized) playbackEngineHost.stateSnapshot() else ""

    /** 记录起播后是否保持暂停。 */
    override fun setPendingPause(paused: Boolean) {
        pendingPause = paused
    }

    /** 最近一次解析得到的 nva 内容。 */
    override fun lastNva(): CastContent? = lastNva

    /** 最近一次投屏标题。 */
    override fun lastTitle(): String = lastTitle

    /** 记录失败重试动作。 */
    override fun setRetry(action: (() -> Unit)?) {
        playbackSessionLifecycle.setRetry(action)
    }

    override fun clearLoginUi() {
        ui = ui.copy(loginQr = null, loginStatus = "")
    }

    /** 重新解析并播放指定 nva 内容。 */
    override fun resolveLast(nva: CastContent) = resolveNvaAndPlay(nva, lastTitle)

    /** 展示错误提示。 */
    override fun reportError(
        title: String,
        message: String,
        code: String,
        canRetry: Boolean,
        keepVideo: Boolean,
    ) = showError(title, message, code, canRetry, keepVideo)

    /** 清除尚未确认的点播线路切换提示。 */
    override fun clearVodPendingSwitch() {
        vodCoordinator.pendingSwitch = null
    }

    /** 更新 DLNA 报告的媒体时长。 */
    override fun setCastDurationMs(durationMs: Long) = dlna.setDurationMs(durationMs)

    /** 读取当前电视端登录令牌。 */
    override fun accessKey(): String? = authTokenStore.load()?.accessKey

    override fun authToken(): CastAuth.Token? = authTokenStore.load()

    /** 可供解析器使用的应用上下文。 */
    override fun appContext(): Context = applicationContext

    /** 播放器是否已经初始化。 */
    override fun isEngineInitialized(): Boolean = ::playbackEngineHost.isInitialized

    /** 返回当前后端的统一播放状态。 */
    override fun snapshot(): PlaybackSnapshot = playbackEngineHost.snapshot(ui.volume)

    override fun isPlaybackVisible(): Boolean = ui.mode == TvMode.PLAYING || ui.mode == TvMode.LOADING
    override fun isBuffering(): Boolean = ui.isBuffering
    override fun displayedDurationMs(): Long = ui.durationMs
    override fun updateProgress(positionMs: Long, durationMs: Long, playing: Boolean, volume: Int, liveBackupCount: Int) {
        ui = ui.copy(
            positionMs = positionMs,
            durationMs = durationMs,
            isPlaying = playing,
            volume = volume,
            liveBackupCount = liveBackupCount,
        )
    }
    override fun updateNetwork(wifiName: String, localIp: String) {
        ui = ui.copy(network = wifiName, ip = localIp)
    }

    /** 当前内容是否为直播。 */
    override fun isLive(): Boolean = currentRequest?.isLive == true

    /** 当前是否存在播放信息。 */
    override fun currentAvailable(): Boolean = currentRequest != null

    /** 当前直播线路之外的备用源数量。 */
    override fun liveBackupCount(): Int = currentRequest?.liveBackupCount ?: 0

    /** 返回 VLC 提供的直播进度与读取统计。 */
    override fun liveTelemetry(): LivePlaybackTelemetry? =
        if (::playbackEngineHost.isInitialized) playbackEngineHost.liveTelemetry() else null

    /** 刷新视频尺寸与弹幕布局。 */
    override fun onRefreshVideoSize() = refreshVideoSize()

    /** 将播放时间同步到弹幕时钟。 */
    override fun onDanmakuPlayerTime(positionMs: Long) {
        if (::danmakuController.isInitialized) danmakuController.onPlayerTime(positionMs)
    }

    /** 将轮询进度交给点播恢复状态机。 */
    override fun onVodProgress(positionMs: Long, activelyPlaying: Boolean) =
        observeVodProgress(positionMs, activelyPlaying)

    /** 上报播放进度到观看历史。 */
    override fun onHistoryPosition(positionMs: Long, durationMs: Long, playing: Boolean) =
        playbackHistoryCoordinator.onPosition(positionMs, durationMs, playing)

    /** 检测直播时间线是否连续无前进。 */
    override fun onLiveProgress(progress: LivePlaybackTelemetry, playing: Boolean) =
        observeLiveProgress(progress, playing)

    /** 当前 Wi-Fi 名称。 */
    override fun wifiName(): String = deviceInfo.wifiName()

    /** 当前设备局域网地址。 */
    override fun localIp(): String = LogHub.localIp()

    /** 供投屏解析使用的协程作用域。 */
    override fun coroutineScope(): CoroutineScope = scope

    /** 当前是否正在解析投屏内容。 */
    override fun isResolving(): Boolean = resolving

    /** 记录解析状态。 */
    override fun setResolving(resolving: Boolean) {
        this.resolving = resolving
    }

    /** 取消当前解析任务。 */
    override fun cancelResolve() {
        resolveJob?.cancel()
    }

    /** 记录当前解析任务。 */
    override fun setResolveJob(job: Job) {
        resolveJob = job
    }

    /** 清除或替换当前播放信息。 */
    override fun setCurrentForCast(request: PlaybackRequest?) {
        currentRequest = request
    }

    /** 记录是否存在活动投屏。 */
    override fun setCastActive(active: Boolean) {
        castActive = active
    }

    /** 将协议原文转换为投屏领域内容身份。 */
    override fun decodeCastContent(longDescription: String): CastContent? =
        NvaExtDecoder.decode(longDescription)

    /** 记录最近一次解析的 nva 内容。 */
    override fun setLastNva(nva: CastContent?) {
        lastNva = nva
    }

    /** 记录最近一次投屏标题。 */
    override fun setLastTitle(title: String) {
        lastTitle = title
    }

    /** 将播放信息写入最近投屏记录。 */
    override fun addRecentRecord(request: PlaybackRequest) {
        val list = recentCastStore.add(ui.recents, request)
        ui = ui.copy(recents = list)
    }

    /** 开始一次新的观看历史会话。 */
    override fun newHistorySession() = playbackHistoryCoordinator.onNewCast()

    /** 结束直播会话与直播专属任务。 */
    override fun endLiveSession(reason: String) = clearLiveSession(reason)

    /** 标记本次投屏失败。 */
    override fun markCastFailed(code: String) = dlna.markCastFailed(code)

    /** 使用已解析的直播信息开始播放。 */
    override fun startLiveSession(
        info: LivePlayInfo,
        routes: List<RankedLiveSource>,
        title: String,
    ) = startLivePlayback(info, routes, title)

    /** 停止当前投屏的播放后端。 */
    override fun stopPlaybackForCast(reason: String) = stopActivePlayback(reason)

    /** 点播会话协调器。 */
    override fun vodSessionCoordinator(): VodPlaybackCoordinator = vodCoordinator

    /** 直播会话协调器。 */
    override fun liveSessionCoordinator(): LivePlaybackCoordinator = liveCoordinator

    /** 直播开播后更新上报使用的媒体地址。 */
    override fun updateLiveUrl(url: String) = playbackHistoryCoordinator.updateLiveUrl(url)

    /** 记录待应用的播放起始位置。 */
    override fun setPendingSeekMs(positionMs: Long) {
        pendingSeekMs = positionMs
    }

    /** 生成用于播放入口日志的内容摘要。 */
    override fun describeContent(request: PlaybackRequest): String =
        "aid=${request.aid} cid=${request.cid} qn=${request.video.id} title=${request.title}"

    /** 按内容能力选择播放后端并打开媒体。 */
    override fun startPlaybackBackend(request: PlaybackRequest, seekToMs: Long): Boolean {
        return runCatching { playbackEngineHost.open(request, seekToMs) }
            .onFailure { error ->
                LogHub.e(
                    TAG,
                    "open playback backend failed drm=${request.isDrm} hdr=${request.video.isHdr} " +
                        "dolbyVision=${request.video.isDolbyVision} error=${LogRedaction.reason(error)}",
                )
            }
            .getOrDefault(false)
    }

    /** 暂停点播弹幕渲染。 */
    override fun onDanmakuClose() {
        if (::danmakuController.isInitialized) danmakuController.close()
    }

    /** 暂停直播弹幕会话。 */
    override fun onLiveDanmakuClose() {
        if (::liveDanmakuController.isInitialized) liveDanmakuController.close()
    }

    /** 打开直播弹幕会话。 */
    override fun onLiveDanmakuOpen(roomId: Long) = openLiveDanmaku(roomId)

    /** 打开点播弹幕会话。 */
    override fun onVodDanmakuOpen(aid: Long, cid: Long, durationMs: Long, seekToMs: Long) {
        if (::danmakuController.isInitialized) {
            danmakuController.open(aid, cid, durationMs, seekToMs)
        }
    }

    /** 同步弹幕速度。 */
    override fun onDanmakuPlaybackRate(rate: Float) {
        if (::danmakuController.isInitialized) danmakuController.onPlaybackRateChanged(rate)
    }

    /** 应用当前弹幕设置。 */
    override fun onDanmakuSettings() {
        if (::danmakuController.isInitialized) danmakuController.applySettings(danmakuSettings)
    }

    /** 重新计算弹幕布局。 */
    override fun onDanmakuLayout() {
        if (::danmakuController.isInitialized) layoutDanmaku()
    }

    /** 恢复各内容类型对应的播放速度。 */
    override fun applyPlaybackRate(request: PlaybackRequest) {
        if (request.isLive) {
            setPlayerPlaybackRate(1f, "live-normal-rate")
            ui = ui.copy(playbackSpeed = 1f)
        } else {
            setPlayerPlaybackRate(ui.playbackSpeed, "play-restore-rate")
        }
    }

    override suspend fun resolveLivePlayback(accessKey: String, roomId: Long): LivePlayInfo =
        livePlaybackResolver.resolve(accessKey, roomId)

    override suspend fun resolveLivePlayback(
        accessKey: String,
        roomId: Long,
        quality: Int,
        forceAvc: Boolean,
    ): LivePlayInfo = livePlaybackResolver.resolve(accessKey, roomId, quality, forceAvc)

    override suspend fun resolveVodPlayback(
        aid: Long,
        cid: Long,
        bvid: String,
        title: String,
        epId: Long,
        seasonId: Long,
    ): PlaybackRequest {
        return playbackResolver.resolveVod(aid, cid, bvid, title, epId, seasonId)
    }

    /** 应用起播前的暂停请求。 */
    override fun applyPendingPause() {
        if (pendingPause) {
            pendingPause = false
            setPlayerPaused(true, "pending-pause")
            ui = ui.copy(isPlaying = false)
        }
    }

    /** 接收 DLNA 渲染器事件并转发给播放器与界面状态。 */
    private val dlnaCommands by lazy { DlnaTransportCoordinator(this) }
    private val dlnaListener by lazy { DlnaCastListenerAdapter(dlnaCommands) }

    override fun onCast(uri: String, title: String, longDescription: String) =
        handleCast(uri, title, longDescription)

    override fun engineAvailable(): Boolean = ::playbackEngineHost.isInitialized
    override fun setPaused(paused: Boolean, reason: String) = setPlayerPaused(paused, reason)
    override fun stopCastFromDlna() = playbackSessionLifecycle.stopFromDlna()
    override fun currentIsLive(): Boolean = currentRequest?.isLive == true
    override fun currentCid(): Long? = currentRequest?.cid
    override fun seekTo(positionMs: Long, reason: String) { seekPlayer(positionMs, reason) }
    override fun seekDanmaku(positionMs: Long) {
        if (::danmakuController.isInitialized) danmakuController.onSeek(positionMs)
    }
    override fun isVlcSeekable(): Boolean = playbackEngineHost.isVlcSeekable()
    override fun setVolume(volume: Int, reason: String) = setPlayerVolume(volume, reason)
    override fun durationMs(): Long = playerDurationMs()
    override fun volume(): Int = playerVolume()
    override fun onMainThread(action: () -> Unit) = runOnUiThread(action)
    override fun showDlnaPlayState(playing: Boolean) { ui = ui.copy(isPlaying = playing, hudVisible = true) }
    override fun showDlnaSeekState(positionMs: Long) { ui = ui.copy(positionMs = positionMs, hudVisible = true) }
    override fun showDlnaVolumeState(volume: Int) { ui = ui.copy(volume = volume, hudVisible = true) }

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
        media3PlayerView = findViewById(R.id.media3PlayerView)
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
            PlaybackEngineHost(
                context = this,
                vlcView = videoLayout,
                media3View = media3PlayerView,
                onEvent = playbackEventRouter::dispatch,
                onHdrWindowChanged = ::setHdrWindowEnabled,
            )
        }.onSuccess { host ->
            playbackEngineHost = host
            danmakuEngine = DfmDanmakuEngine(danmakuView, danmakuClock)
            danmakuController = DanmakuController(
                scope, danmakuEngine, danmakuClock,
                DanmakuRepository(DanmakuApi(authTokenStore)),
            )
            liveDanmakuController = LiveDanmakuController(this, scope, danmakuEngine, danmakuClock)
            danmakuController.applySettings(danmakuSettings)
            liveDanmakuController.applySettings(danmakuSettings)
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
        val syncPlaybackHistory = uiPreferences.syncPlaybackHistory
        ui = ui.copy(
            rememberPlaybackContent = rememberPlaybackContent,
            syncPlaybackHistory = syncPlaybackHistory,
            recents = if (rememberPlaybackContent) recentCastStore.load() else emptyList(),
            showContinue = rememberPlaybackContent,
        )
        playbackHistoryCoordinator
        startOrLogin()
        startTicker()
        updateCoordinator.checkAutomaticallyOnLaunch()
    }

    /** 播放器事件只经统一路由进入会话逻辑。 */
    override fun activeBackend(): PlaybackBackend = playbackEngineHost.backend
    override fun currentPlaybackRequest(): PlaybackRequest? = currentRequest
    override fun consumePendingSeekMs(): Long = pendingSeekMs.also { pendingSeekMs = 0L }
    override fun playerStateSnapshot(): String = playbackEngineHost.stateSnapshot()
    override fun runOnMain(action: () -> Unit) { runOnUiThread { action() } }
    override fun vodOnPlaying() = vodCoordinator.onPlaying()
    override fun handleVodFailure(reason: String, preferCodecFallback: Boolean): Boolean =
        handleVodPlaybackFailure(reason, preferCodecFallback)
    override fun onLivePlaying() {
        if (::liveDanmakuController.isInitialized) liveDanmakuController.onPlaying()
        liveCoordinator.health.onPlaying()
        liveCoordinator.stallWatchdog.reset()
        completeLiveRouteSwitch()
    }
    override fun onLivePaused() {
        if (::liveDanmakuController.isInitialized) liveDanmakuController.onPaused()
    }
    override fun onLiveBuffering(buffering: Boolean): Boolean = liveCoordinator.health.onBuffering(buffering)
    override fun handleLiveFailure(reason: String) { handleLivePlaybackFailure(reason) }
    override fun finishHistory(reason: String, ended: Boolean) {
        playbackHistoryCoordinator.finish(reason, ended)
    }
    override fun historyOnPlaying() {
        currentRequest?.let { info ->
            playbackHistoryCoordinator.onPlaying(
                content = playbackHistoryContent(info),
                positionMs = playerPositionMs(),
                durationMs = playerDurationMs(),
            )
        }
    }
    override fun historyOnPaused() {
        playbackHistoryCoordinator.onPaused(playerPositionMs(), playerDurationMs())
    }
    override fun setCastEnded() {
        castActive = false
        currentRequest = null
    }
    override fun setRetryFor(request: PlaybackRequest) {
        playbackSessionLifecycle.setRetry { castCoordinator.openPlayback(request, playerPositionMs()) }
    }
    override fun updateMedia3Playing(seek: Long) {
        ui = ui.copy(
            mode = TvMode.PLAYING,
            isPlaying = true,
            isBuffering = false,
            positionMs = if (seek > 0L) seek else playerPositionMs(),
            durationMs = playerDurationMs().takeIf { it > 0L } ?: ui.durationMs,
        )
    }
    override fun updateVlcPlaying(seek: Long) {
        ui = ui.copy(
            mode = TvMode.PLAYING,
            isPlaying = true,
            isBuffering = false,
            positionMs = if (seek > 0L) seek else ui.positionMs,
        )
        val duration = playerDurationMs()
        if (duration > 0L) ui = ui.copy(durationMs = duration)
    }
    override fun updatePaused() { ui = ui.copy(isPlaying = false) }
    override fun updateStopped() { ui = ui.copy(isPlaying = false, isBuffering = false) }
    override fun updateBuffering(buffering: Boolean) { ui = ui.copy(isBuffering = buffering) }
    override fun updateEnded() {
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
    override fun updateVideoSize(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
        ui = ui.copy(videoWidth = width, videoHeight = height)
        layoutDanmaku()
    }
    override fun vodDanmakuPlaying(seek: Long) {
        if (::danmakuController.isInitialized) {
            val position = if (seek > 0L) seek else playerPositionMs()
            danmakuController.onPlaying(position)
            if (seek > 0L) danmakuController.onSeek(seek)
        }
    }
    override fun vodDanmakuPaused() {
        if (::danmakuController.isInitialized) danmakuController.onPaused(playerPositionMs())
    }
    override fun vodDanmakuBuffering(buffering: Boolean) {
        if (::danmakuController.isInitialized) danmakuController.onBuffering(buffering, playerPositionMs())
    }
    override fun vodDanmakuClose() {
        if (::danmakuController.isInitialized) danmakuController.close()
    }
    override fun liveDanmakuClose() {
        if (::liveDanmakuController.isInitialized) liveDanmakuController.close()
    }
    override fun reportPlaybackError(
        title: String, message: String, code: String, canRetry: Boolean, keepVideo: Boolean,
    ) { showError(title, message, code, canRetry, keepVideo) }

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

    /** 根据最近投屏记录重新解析并开始播放。 */
    private fun recast(recent: RecentCast) = castCoordinator.recast(recent)

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
    private fun handleCast(uri: String, title: String, longDescription: String) =
        castCoordinator.handleCast(uri, title, longDescription)

    /** 根据 nva 内容解析播放地址并开始播放。 */
    private fun resolveNvaAndPlay(nva: CastContent, title: String) =
        castCoordinator.resolve(nva, title)

    /** 采用首次线路测速结果启动直播。 */
    private fun startLivePlayback(stream: LivePlayInfo, routes: List<RankedLiveSource>, title: String) =
        liveCoordinator.start(stream, routes, title)

    /** 定时刷新直播备用线路排序。 */
    private fun startLiveBackupRefresh(session: LiveSession) = liveCoordinator.startBackupRefresh(session)

    /** 打开直播线路失败时按候选顺序继续尝试。 */
    private suspend fun handleLiveRouteOpenFailure(reason: String) =
        liveCoordinator.handleLiveRouteOpenFailure(reason)

    /** 处理直播播放故障并触发降级。 */
    private fun handleLivePlaybackFailure(reason: String) = liveCoordinator.handleFailure(reason)

    /** 切换到指定直播线路。 */
    private fun switchLiveRoute(
        target: RankedLiveSource,
        reason: String,
        from: LiveStreamCandidate?,
    ): Boolean = liveCoordinator.switchRoute(target, reason, from)

    /** 继续尝试剩余直播候选项。 */
    private suspend fun continueLiveFallback(reason: String) = liveCoordinator.continueFallback(reason)

    /** 重新请求 AVC 或较低档位，并在新候选中完成首播选路。 */
    private suspend fun resolveLiveFallback(
        session: LiveSession,
        quality: Int,
        forceAvc: Boolean,
        reason: String,
    ) = liveCoordinator.resolveFallback(session, quality, forceAvc, reason)

    /** 直播线路耗尽后展示失败提示。 */
    private fun failLivePlayback(code: String, message: String) = liveCoordinator.fail(code, message)

    /** 播放稳定后结束直播切源事务。 */
    private fun completeLiveRouteSwitch() = liveCoordinator.completeRouteSwitch()

    /** 清理直播专属任务和阈值，防止用户停止后触发后台自动重连。 */
    override fun clearLiveSession(reason: String) {
        val session = liveCoordinator.activeSession
        liveCoordinator.clear()
        val oldVodSession = vodCoordinator.activeSession
        vodCoordinator.clear()
        if (::liveDanmakuController.isInitialized) liveDanmakuController.close()
        if (session != null) LogHub.i(TAG, "live session cleared room=${session.stream.roomId} reason=$reason")
        if (oldVodSession != null) LogHub.i(TAG, "VOD session cleared reason=$reason")
    }


    /** 将播放器缓冲事件交给点播恢复状态机。 */
    override fun observeVodBuffering(buffering: Boolean) = vodCoordinator.observeBuffering(buffering)

    /** 处理点播播放故障，优先在同表示层内切换线路。 */
    private fun handleVodPlaybackFailure(reason: String, preferCodecFallback: Boolean = false): Boolean =
        vodCoordinator.handleFailure(reason, preferCodecFallback)

    /** 播放稳定后结束切源事务并提示用户。 */
    private fun completeVodRouteSwitch() = vodCoordinator.completeRouteSwitch()

    /** 在稳定播放态下检测无进度并处理恢复超时。 */
    private fun observeVodProgress(positionMs: Long, activelyPlaying: Boolean) =
        vodCoordinator.observeProgress(positionMs, activelyPlaying)

    /** 每 30 秒刷新备用线路排序，不更改当前播放地址。 */
    private fun startVodBackupRefresh() = vodCoordinator.startBackupRefresh()

    /** 直播 qn 与点播 qn 数值体系不同，使用直播清晰度名称。 */
    private fun liveQualityLabel(qn: Int): String = LiveQualityMapper.label(qn)

    /** 直播可选画质档位，取服务端公布的可用档位并映射为友好名称。 */
    private fun liveQualityOptions(session: LiveSession): List<LiveQualityOption> =
        LiveQualityMapper.options(session.stream)

    /** 打开直播弹幕会话；复用登录 Cookie 换取进房 token。 */
    private fun openLiveDanmaku(roomId: Long) {
        if (!::liveDanmakuController.isInitialized) return
        val token = authTokenStore.load()
        liveDanmakuController.open(
            roomId = roomId,
            cookie = token?.cookie.orEmpty(),
            uid = token?.mid ?: 0L,
        )
        liveDanmakuController.applySettings(danmakuSettings)
        layoutDanmaku()
    }

    private fun liveProbeOrder(): Comparator<RankedLiveSource> = LiveQualityMapper.probeOrder()

    private fun switchQuality(qn: Int) = playbackSelectionCoordinator.switchQuality(qn)
    private fun switchAudio(id: Int) = playbackSelectionCoordinator.switchAudio(id)

    /** 切换播放器的播放与暂停状态。 */
    private fun togglePlayPause() = playbackCoordinator.togglePlayPause()

    /** 按指定毫秒数调整当前播放位置。 */
    private fun seekBy(ms: Long) = playbackCoordinator.seekBy(ms)

    /**
     * 预览性地跳转到新位置。
     *
     * 每次方向键输入都会立即驱动 VLC 跳转，以便画面加载目标位置；待确认状态只用于
     * 控制进度条显示与自动提交，不会延后播放器的实际定位。
     */
    private fun previewSeekBy(ms: Long) = playbackCoordinator.previewSeekBy(ms)

    /** 结束当前预览会话；播放器已在预览期间定位到目标位置。 */
    private fun confirmPreviewSeek() = playbackCoordinator.confirmPreviewSeek()

    /** 将 VLC 与弹幕时钟切换到相同的标准播放速度。 */
    private fun setPlaybackSpeed(rate: Float) = playbackCoordinator.setPlaybackSpeed(rate)

    /** 更新弹幕设置并持久化。 */
    private fun updateDanmakuSettings(newSettings: DanmakuSettings) {
        danmakuSettings = newSettings
        danmakuPreferences.save(newSettings)
        if (::danmakuController.isInitialized) {
            danmakuController.applySettings(newSettings)
        }
        if (::liveDanmakuController.isInitialized) {
            liveDanmakuController.applySettings(newSettings)
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
            if (::liveDanmakuController.isInitialized) liveDanmakuController.setContentHeight(screenH)
            val expectedWidth = screenW
            val expectedHeight = areaH
            danmakuView.post {
                if (danmakuView.width != expectedWidth || danmakuView.height != expectedHeight) {
                    return@post
                }
                LogHub.d(TAG, "prepare danmaku viewport=${expectedWidth}x$expectedHeight")
                danmakuController.prepare()
                if (::liveDanmakuController.isInitialized) liveDanmakuController.prepare()
            }
        }
    }

    /** 从播放器读取视频尺寸，变化时重新摆放弹幕视图。 */
    override fun refreshVideoSize() {
        if (playbackEngineHost.backend == PlaybackBackend.MEDIA3) return
        if (!::playbackEngineHost.isInitialized) return
        val size = playbackEngineHost.videoSize() ?: return
        val liveCodec = currentRequest?.takeIf { it.isLive }?.video?.codecs
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
            currentRequest?.let { LogHub.setPlay(describe(it)) }
        }
        layoutDanmaku()
    }

    /** 设置播放器音量并同步界面状态。 */
    /** 设置音量，沿用界面的 0..100 标度。 */
    private fun setVolume(volume: Int) = playbackCoordinator.setVolume(volume)

    private fun retryLastAction() = playbackSessionLifecycle.retry()
    private fun dismissError() = playbackSessionLifecycle.dismissError()
    private fun stopCasting() = playbackSessionLifecycle.stopCasting()
    private fun showError(
        title: String, message: String, code: String, canRetry: Boolean, keepVideo: Boolean = false,
    ) = playbackSessionLifecycle.showError(title, message, code, canRetry, keepVideo)

    override fun modeForLog(): String = ui.mode.toString()
    override fun finishSessionHistory(reason: String) { playbackHistoryCoordinator.finish(reason) }
    override fun hasCurrentRequest(): Boolean = currentRequest != null
    override fun lastCastContent(): CastContent? = lastNva
    override fun lastCastTitle(): String = lastTitle
    override fun resolveCast(content: CastContent, title: String) = resolveNvaAndPlay(content, title)
    override fun cancelResolveJob() {
        resolveJob?.cancel()
        resolveJob = null
    }
    override fun resetPendingPlayback() {
        pendingPause = false
        pendingSeekMs = 0L
    }
    override fun clearPendingPause() { pendingPause = false }
    override fun stopPlaybackForSession(reason: String) = stopActivePlayback(reason)
    override fun clearSessionIdentity(clearCastMetadata: Boolean, clearResolving: Boolean) {
        currentRequest = null
        castActive = false
        if (clearResolving) resolving = false
        if (clearCastMetadata) {
            lastNva = null
            lastTitle = ""
        }
    }
    override fun showRetryState(hasCurrent: Boolean) {
        ui = ui.copy(
            mode = if (hasCurrent) TvMode.PLAYING else TvMode.LOADING,
            error = null,
            hudVisible = true,
        )
    }
    override fun showDismissedState() {
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
    override fun showStoppedState() {
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
    override fun showErrorState(title: String, message: String, code: String, canRetry: Boolean) {
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
    override fun flushHistoryPosition() {
        playbackHistoryCoordinator.onPosition(
            positionMs = playerPositionMs(),
            durationMs = playerDurationMs(),
            playing = playerIsPlaying(),
        )
    }
    override fun closeHistory() { playbackHistoryCoordinator.close() }
    override fun showDlnaStoppedState() {
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

    /** 首页连续两次返回退出，首按显示明确提示。 */
    private fun handleHomeBackInternal() {
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
    override fun playerPositionMs(): Long = playbackEngineHost.snapshot(ui.volume).positionMs

    override fun playerDurationMs(): Long = playbackEngineHost.snapshot(ui.volume).durationMs

    private fun playerIsPlaying(): Boolean = playbackEngineHost.snapshot(ui.volume).isPlaying

    private fun playerVolume(): Int = playbackEngineHost.snapshot(ui.volume).volume

    private fun seekPlayer(positionMs: Long, reason: String): Long {
        if (currentRequest?.isLive != true) {
            vodCoordinator.onSeek(positionMs)
        }
        return playbackEngineHost.seekTo(positionMs, reason)
    }

    private fun setPlayerPaused(paused: Boolean, reason: String) {
        playbackEngineHost.setPaused(paused, reason)
    }

    private fun setPlayerPlaybackRate(rate: Float, reason: String): Float =
        playbackEngineHost.setPlaybackRate(rate, reason)

    private fun setPlayerVolume(volume: Int, reason: String) {
        playbackEngineHost.setVolume(volume, reason)
    }

    private fun stopActivePlayback(reason: String) {
        playbackEngineHost.stop(reason)
        setHdrWindowEnabled(false)
    }

    /** HDR 视频进入专用显示模式，结束或切回 VLC 时立即恢复系统默认色彩模式。 */
    private fun setHdrWindowEnabled(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mode = if (enabled) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
        runCatching { window.colorMode = mode }
            .onSuccess {
                PlaybackDiagnostics.attribute("renderer.window.colorMode", if (enabled) "hdr" else "default")
                LogHub.i(TAG, "HDR window color mode=${if (enabled) "hdr" else "default"}")
            }
            .onFailure { error ->
                LogHub.e(TAG, "HDR window color mode update failed: ${LogRedaction.reason(error)}")
            }
    }

    /** 启动定时任务以同步播放进度和设备网络信息。 */
    private fun startTicker() = playbackTicker.start()

    /** 检查直播时间线、网络读取或解码画面是否连续五秒没有前进。 */
    private fun observeLiveProgress(
        progress: LivePlaybackTelemetry,
        playing: Boolean,
    ) {
        val now = SystemClock.elapsedRealtime()
        val stalled = liveCoordinator.stallWatchdog.observe(
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
                    "room=${liveCoordinator.activeSession?.stream?.roomId} $detail",
            )
            PlaybackDiagnostics.event(
                "LivePlaybackStalled",
                "room=${liveCoordinator.activeSession?.stream?.roomId} duration=${LIVE_STALL_TIMEOUT_MS}ms $detail",
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

    /** 将当前媒体与投屏 NVA 身份映射到 B 站 TV 历史业务类型。 */
    private fun playbackHistoryContent(info: PlaybackRequest): PlaybackHistoryContent {
        val nva = lastNva?.takeIf { candidate ->
            if (info.isLive) {
                candidate.roomId > 0L && (info.cid <= 0L || candidate.roomId == info.cid)
            } else if (info.isPgc) {
                (info.epId <= 0L || candidate.epId <= 0L || candidate.epId == info.epId) &&
                    (info.seasonId <= 0L || candidate.seasonId <= 0L || candidate.seasonId == info.seasonId)
            } else {
                (info.aid <= 0L || candidate.aid <= 0L || candidate.aid == info.aid) &&
                    (info.cid <= 0L || candidate.cid <= 0L || candidate.cid == info.cid)
            }
        }
        if (info.isLive) {
            val roomId = nva?.roomId?.takeIf { it > 0L } ?: info.cid
            return PlaybackHistoryContent(
                stableKey = "live:$roomId",
                bvid = "",
                aid = 0L,
                cid = roomId,
                sid = 0L,
                epid = 0L,
                serialId = 0L,
                type = HISTORY_TYPE_LIVE,
                business = HISTORY_BUSINESS_LIVE,
                isLive = true,
                roomId = roomId,
                livePlayUrl = info.video.url,
            )
        }

        val isPgc = info.isPgc || (nva != null && (nva.epId > 0L || nva.seasonId > 0L))
        val aid = info.aid.takeIf { it > 0L } ?: nva?.aid ?: 0L
        val cid = info.cid.takeIf { it > 0L } ?: nva?.cid ?: 0L
        val sid = if (isPgc) info.seasonId.takeIf { it > 0L } ?: nva?.seasonId ?: 0L else 0L
        val epid = if (isPgc) info.epId.takeIf { it > 0L } ?: nva?.epId ?: 0L else 0L
        val type = if (isPgc) HISTORY_TYPE_PGC else HISTORY_TYPE_UGC
        return PlaybackHistoryContent(
            stableKey = if (isPgc) "pgc:$sid:$epid:$cid" else "ugc:$aid:$cid",
            bvid = info.bvid,
            aid = aid,
            cid = cid,
            sid = sid,
            epid = epid,
            serialId = 0L,
            type = type,
            business = if (isPgc) HISTORY_BUSINESS_PGC else HISTORY_BUSINESS_ARCHIVE,
            isLive = false,
            roomId = 0L,
            livePlayUrl = "",
        )
    }

    /** 将播放信息整理为诊断日志摘要。 */
    private fun describe(info: PlaybackRequest?): String {
        if (info == null) return ""
        val v = info.video
        val width = if (info.isLive && ui.videoWidth > 0) ui.videoWidth else v.width
        val height = if (info.isLive && ui.videoHeight > 0) ui.videoHeight else v.height
        val codec = v.codecs.ifBlank { VlcTrackFormat.codecName(v.codecId) }
        val audio = if (info.isLive) "自动音轨" else info.audioLabel
        return "${info.title}\n${info.qualityLabel}  ${width}x$height  " +
            "codec=$codec  $audio\n" +
            "aid=${info.aid} cid=${info.cid}  duration=${info.durationSec}s  " +
            "progressive=${info.progressiveUrl != null}  backup=${v.backupUrls.size}\n" +
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
        if (!castActive && ::playbackEngineHost.isInitialized) {
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
        playbackSessionLifecycle.destroy()
        runCatching { dlna.stop() }
        loginCoordinator.cancel()
        updateCoordinator.cancel()
        resolveJob?.cancel()
        playbackTicker.stop()
        runCatching { if (::danmakuController.isInitialized) danmakuController.release() }
        runCatching { if (::liveDanmakuController.isInitialized) liveDanmakuController.release() }
        scope.cancel()
        runCatching { if (::playbackEngineHost.isInitialized) playbackEngineHost.release() }
        LogHub.stop()
    }

    companion object {
        /** 日志标签。 */
        private const val TAG = "SimpleBili"
        /** 首页双击返回退出计时窗口。 */
        private const val HOME_BACK_EXIT_WINDOW_MS = HomeBackExitTiming.EXIT_WINDOW_MS
        /** 直播备用线路评分刷新周期。 */
        private const val LIVE_BACKUP_REFRESH_INTERVAL_MS = 30_000L
        /** 点播备用线路评分刷新周期。 */
        private const val VOD_BACKUP_REFRESH_INTERVAL_MS = 30_000L
        /** 直播播放位置无前进时触发故障切源的时间阈值。 */
        private const val LIVE_STALL_TIMEOUT_MS = 5_000L
        /** 点播播放位置无前进时触发故障切源的时间阈值。 */
        private const val VOD_STALL_TIMEOUT_MS = 5_000L
        /** B 站 TV 历史接口的业务类型：普通稿件。 */
        private const val HISTORY_TYPE_UGC = 3
        /** B 站 TV 历史接口的业务类型：番剧等 PGC。 */
        private const val HISTORY_TYPE_PGC = 4
        /** B 站 TV 历史接口的业务类型：直播。 */
        private const val HISTORY_TYPE_LIVE = 6
        private const val HISTORY_BUSINESS_ARCHIVE = "archive"
        private const val HISTORY_BUSINESS_PGC = "pgc"
        private const val HISTORY_BUSINESS_LIVE = "live"
        /** 是否已经安装全局崩溃记录器。 */
        @Volatile
        private var crashHandlerInstalled = false
    }

}
