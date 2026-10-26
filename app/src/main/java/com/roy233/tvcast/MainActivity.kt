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
import com.roy233.tvcast.ui.model.RecentCast
import com.roy233.tvcast.ui.model.SettingsPage
import com.roy233.tvcast.ui.model.TvDestination
import com.roy233.tvcast.ui.model.TvError
import com.roy233.tvcast.ui.model.TvMode
import com.roy233.tvcast.ui.model.TvUiState
import com.roy233.tvcast.ui.model.PlaybackUiState
import com.roy233.tvcast.ui.model.playbackUiState
import com.roy233.tvcast.ui.model.withPlaybackUiState
import com.roy233.tvcast.ui.tv.TvRoot
import com.roy233.tvcast.app.CapyCastContainer
import com.roy233.tvcast.app.PlaybackEngineHost
import com.roy233.tvcast.ui.tv.TvActionDispatcher
import com.roy233.tvcast.ui.tv.TvActionHost
import com.roy233.tvcast.ui.tv.TvStateHolder
import com.roy233.tvcast.ui.tv.DoubleBackExitGate
import com.roy233.tvcast.ui.tv.HomeBackExitTiming
import com.roy233.tvcast.ui.tv.TvKeyHandler
import com.roy233.tvcast.data.bili.BiliApi
import com.roy233.tvcast.data.bili.CdnCandidateSelector
import com.roy233.tvcast.data.bili.LivePlayUrlResolver
import com.roy233.tvcast.data.bili.routing.OkHttpLiveRouteProbe
import com.roy233.tvcast.data.bili.routing.OkHttpVodRouteProbe
import com.roy233.tvcast.data.bili.PgcPlaybackException
import com.roy233.tvcast.data.bili.PgcPlaybackResolver
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
import com.roy233.tvcast.data.bili.mapper.BiliPlaybackMapper
import com.roy233.tvcast.feature.playback.application.PlaybackCoordinator
import com.roy233.tvcast.feature.playback.application.VodPlaybackCoordinator
import com.roy233.tvcast.feature.playback.application.LivePlaybackCoordinator
import com.roy233.tvcast.feature.playback.application.LivePlaybackHost
import com.roy233.tvcast.feature.playback.application.CastSessionCoordinator
import com.roy233.tvcast.feature.playback.application.CastSessionHost
import com.roy233.tvcast.feature.playback.application.PlaybackTicker
import com.roy233.tvcast.feature.playback.application.PlaybackTickerHost
import com.roy233.tvcast.feature.playback.application.PlaybackEventConsumer
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
import com.roy233.tvcast.feature.history.PlaybackHistoryContent
import com.roy233.tvcast.feature.update.UpdateChannel
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
class MainActivity : ComponentActivity(), TvActionHost, VodPlaybackHost, LivePlaybackHost, PlaybackTickerHost, CastSessionHost, PlaybackEventConsumer {

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
    /** 播放进度轮询任务。 */
    private var tickerJob: Job? = null

    /** 当前播放信息。 */
    private var current: BiliApi.PlayInfo? = null
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
    private var lastNva: NvaExtDecoder.NvaExtContent? = null
    /** 最近一次投屏标题。 */
    private var lastTitle = ""
    /** 最近一次失败操作的重试回调。 */
    private var lastErrorRetry: (() -> Unit)? = null
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

    /** 通用播放控制协调器。 */
    private val playbackCoordinator by lazy {
        PlaybackCoordinator(
            readState = { ui.playbackUiState() },
            writeState = { state -> ui = ui.withPlaybackUiState(state) },
            isEngineInitialized = { ::playbackEngineHost.isInitialized },
            isLive = { current?.isLive == true },
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

    /** 返回当前电视界面状态。 */
    override fun state(): PlaybackUiState = ui.playbackUiState()

    /** 提交新的电视界面状态。 */
    override fun setState(state: PlaybackUiState) {
        ui = ui.withPlaybackUiState(state)
    }

    /** 记录当前播放信息。 */
    fun setCurrent(info: BiliApi.PlayInfo) {
        current = info
        currentRequest = BiliPlaybackMapper.map(info)
    }

    /** 返回当前直播会话使用的服务端播放信息。 */
    fun currentInfo(): BiliApi.PlayInfo? = current

    override fun currentRequest(): PlaybackRequest? = currentRequest

    override fun setCurrent(request: PlaybackRequest) {
        currentRequest = request
        current = current?.let { info ->
            val video = info.video.takeIf { it.id == request.video.id }?.copy(
                url = request.video.url,
                backup = request.video.backupUrls,
                pcdn = request.video.isPcdn,
            ) ?: info.video
            val audio = info.audio?.takeIf { it.id == request.audio?.id }?.let { track ->
                val source = request.audio ?: return@let track
                track.copy(url = source.url, backup = source.backupUrls, pcdn = source.isPcdn)
            }
            info.copy(video = video, audio = audio)
        }
    }

    /** 当前是否存在活动投屏。 */
    override fun isCastActive(): Boolean = castActive

    /** 当前播放位置，单位为毫秒。 */
    override fun positionMs(): Long = playerPositionMs()

    /** 使用指定播放信息打开播放器。 */
    fun openPlayback(info: BiliApi.PlayInfo, seekToMs: Long): Boolean = play(info, seekToMs)

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
    override fun lastNva(): NvaExtDecoder.NvaExtContent? = lastNva

    /** 最近一次投屏标题。 */
    override fun lastTitle(): String = lastTitle

    /** 记录失败重试动作。 */
    override fun setRetry(action: (() -> Unit)?) {
        lastErrorRetry = action
    }

    override fun clearLoginUi() {
        ui = ui.copy(loginQr = null, loginStatus = "")
    }

    /** 重新解析并播放指定 nva 内容。 */
    override fun resolveLast(nva: NvaExtDecoder.NvaExtContent) = resolveNvaAndPlay(nva, lastTitle)

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
    override fun authToken(): CastAuth.Token? = authTokenStore.load()

    /** 可供解析器使用的应用上下文。 */
    override fun appContext(): Context = applicationContext

    /** 播放器是否已经初始化。 */
    override fun isEngineInitialized(): Boolean = ::playbackEngineHost.isInitialized

    /** 返回当前后端的统一播放状态。 */
    override fun snapshot(): PlaybackSnapshot = playbackEngineHost.snapshot(ui.volume)

    /** 当前内容是否为直播。 */
    override fun isLive(): Boolean = current?.isLive == true

    /** 当前是否存在播放信息。 */
    override fun currentAvailable(): Boolean = current != null

    /** 当前直播线路之外的备用源数量。 */
    override fun liveBackupCount(): Int = current?.liveBackupCount ?: 0

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

    /** 记录最近一次解析的 nva 内容。 */
    override fun setLastNva(nva: NvaExtDecoder.NvaExtContent?) {
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
        LivePlayUrlResolver.resolve(this, accessKey, roomId)

    override suspend fun resolveLivePlayback(
        accessKey: String,
        roomId: Long,
        quality: Int,
        forceAvc: Boolean,
    ): LivePlayInfo = LivePlayUrlResolver.resolve(this, accessKey, roomId, quality, forceAvc)

    override suspend fun resolveVodPlayback(
        aid: Long,
        cid: Long,
        bvid: String,
        title: String,
        epId: Long,
        seasonId: Long,
    ): PlaybackRequest {
        val info = if (epId > 0L || seasonId > 0L) {
            PgcPlaybackResolver().resolve(epId, seasonId, cid, aid, bvid, title)
        } else {
            BiliApi.resolveByAidCid(aid, cid, title, bvid)
        }
        return BiliPlaybackMapper.map(info)
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
            if (::playbackEngineHost.isInitialized) {
                setPlayerPaused(false, "dlna-play")
            } else {
                LogHub.w(TAG, "dlna play ignored: player engine unavailable")
            }
            runOnUiThread { ui = ui.copy(isPlaying = true, hudVisible = true) }
        }

        /** 处理远端暂停命令。 */
        override fun onPause() {
            pendingPause = true
            if (::playbackEngineHost.isInitialized) {
                setPlayerPaused(true, "dlna-pause")
            } else {
                LogHub.w(TAG, "dlna pause ignored: player engine unavailable")
            }
            runOnUiThread { ui = ui.copy(isPlaying = false, hudVisible = true) }
        }

        /** 处理远端停止命令。 */
        override fun onStop() {
            pendingPause = false
            playbackHistoryCoordinator.finish("dlna-stop")
            clearLiveSession("dlna-stop")
            if (::playbackEngineHost.isInitialized) {
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
            if (::playbackEngineHost.isInitialized) {
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
            playbackEngineHost.isVlcSeekable()

        /** 处理远端音量设置命令。 */
        override fun onSetVolume(volume: Int) {
            val v = volume.coerceIn(0, 100)
            if (::playbackEngineHost.isInitialized) {
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
            danmakuController = DanmakuController(scope, danmakuEngine, danmakuClock)
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

    /** 根据当前后端将统一事件交给对应的播放状态处理逻辑。 */
    override fun onPlaybackEvent(backend: PlaybackBackend, event: PlaybackEvent) {
        when (backend) {
            PlaybackBackend.VLC -> handleVlcPlaybackEvent(event)
            PlaybackBackend.MEDIA3 -> handleMedia3PlaybackEvent(event)
        }
    }

    /** 处理 Media3 事件并保持 HDR、杜比视界与 DRM 的状态转换。 */
    private fun handleMedia3PlaybackEvent(event: PlaybackEvent) {
        if (playbackEngineHost.backend != PlaybackBackend.MEDIA3) return
        when (event) {
            PlaybackEvent.Playing -> handleDolbyVisionPlaying()
            PlaybackEvent.Paused -> handleDolbyVisionPaused()
            PlaybackEvent.Stopped -> Unit
            PlaybackEvent.Ended -> handleDolbyVisionEnded()
            is PlaybackEvent.Buffering -> handleDolbyVisionBuffering(event.isBuffering)
            is PlaybackEvent.Failed -> handleDolbyVisionError(event)
            is PlaybackEvent.VideoSizeChanged -> {
                videoWidth = event.width
                videoHeight = event.height
                ui = ui.copy(videoWidth = event.width, videoHeight = event.height)
                layoutDanmaku()
            }
        }
    }

    /** Media3 的状态回调映射为现有 UI 与弹幕状态机。 */
    private fun handleDolbyVisionPlaying() {
        if (playbackEngineHost.backend != PlaybackBackend.MEDIA3) return
        vodCoordinator.onPlaying()
        val seek = pendingSeekMs
        pendingSeekMs = 0L
        ui = ui.copy(
            mode = TvMode.PLAYING,
            isPlaying = true,
            isBuffering = false,
            positionMs = if (seek > 0L) seek else playerPositionMs(),
            durationMs = playerDurationMs().takeIf { it > 0L } ?: ui.durationMs,
        )
        current?.let { info ->
            playbackHistoryCoordinator.onPlaying(
                content = playbackHistoryContent(info),
                positionMs = playerPositionMs(),
                durationMs = playerDurationMs(),
            )
        }
        if (::danmakuController.isInitialized) {
            val position = if (seek > 0L) seek else playerPositionMs()
            danmakuController.onPlaying(position)
            if (seek > 0L) danmakuController.onSeek(seek)
        }
    }

    private fun handleDolbyVisionPaused() {
        if (playbackEngineHost.backend != PlaybackBackend.MEDIA3) return
        ui = ui.copy(isPlaying = false)
        playbackHistoryCoordinator.onPaused(playerPositionMs(), playerDurationMs())
        if (::danmakuController.isInitialized) danmakuController.onPaused(playerPositionMs())
    }

    private fun handleDolbyVisionBuffering(buffering: Boolean) {
        if (playbackEngineHost.backend != PlaybackBackend.MEDIA3) return
        ui = ui.copy(isBuffering = buffering)
        observeVodBuffering(buffering)
        if (::danmakuController.isInitialized) {
            danmakuController.onBuffering(buffering, playerPositionMs())
        }
    }

    private fun handleDolbyVisionEnded() {
        if (playbackEngineHost.backend != PlaybackBackend.MEDIA3) return
        playbackHistoryCoordinator.finish("media3-ended", ended = true)
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

    private fun handleDolbyVisionError(error: PlaybackEvent.Failed) {
        if (playbackEngineHost.backend != PlaybackBackend.MEDIA3) return
        val decoderFailure = error.code.contains("DECODING", ignoreCase = true) ||
            error.code.contains("RENDERER", ignoreCase = true) ||
            error.code.contains("CODEC", ignoreCase = true)
        if (handleVodPlaybackFailure("media3-player-error", preferCodecFallback = decoderFailure)) return
        playbackHistoryCoordinator.finish("media3-error")
        val info = current
        lastErrorRetry = info?.let { currentInfo -> { play(currentInfo, playerPositionMs()) } }
        val licenseFailure = error.code.contains("DRM", ignoreCase = true) ||
            error.code.contains("LICENSE", ignoreCase = true)
        val code = when {
            info?.isDrm != true -> "DOLBY_VISION_PLAYBACK_FAILED"
            licenseFailure -> "WIDEVINE_LICENSE_FAILED"
            else -> "WIDEVINE_PLAYBACK_FAILED"
        }
        val title = if (info?.isDrm == true) "受保护内容播放失败" else "杜比视界播放失败"
        val errorSummary = "Media3 playback failed drm=${info?.isDrm == true} " +
            "code=${error.code} message=${error.message}"
        LogHub.e(TAG, errorSummary)
        PlaybackDiagnostics.fail(
            code = code,
            message = title,
            detail = "error=${error.code} message=${error.message}",
        )
        showError(
            title = title,
            message = if (info?.isDrm == true) "Widevine 授权或设备安全解码器不可用，请查看诊断日志。" else "当前设备、系统或 HDMI 链路无法播放此杜比视界码流。",
            code = code,
            canRetry = info != null,
            keepVideo = true,
        )
        if (::danmakuController.isInitialized) danmakuController.close()
    }

    /** 处理 VLC 后端转换后的播放事件。 */
    private fun handleVlcPlaybackEvent(event: PlaybackEvent) {
        if (playbackEngineHost.backend != PlaybackBackend.VLC) return
        when (event) {
            PlaybackEvent.Playing -> {
                val seek = pendingSeekMs
                pendingSeekMs = 0L
                LogHub.i(TAG, "ui playing initialPosition=${seek}ms")
                runOnUiThread {
                    vodCoordinator.onPlaying()
                    ui = ui.copy(
                        mode = TvMode.PLAYING,
                        isPlaying = true,
                        isBuffering = false,
                        positionMs = if (seek > 0) seek else ui.positionMs,
                    )
                    val duration = playerDurationMs()
                    if (duration > 0) {
                        ui = ui.copy(durationMs = duration)
                    }
                    refreshVideoSize()
                    if (current?.isLive == true) {
                        if (::liveDanmakuController.isInitialized) liveDanmakuController.onPlaying()
                        liveCoordinator.health.onPlaying()
                        liveCoordinator.stallWatchdog.reset()
                        completeLiveRouteSwitch()
                    } else if (::danmakuController.isInitialized) {
                        val position = if (seek > 0) {
                            seek
                        } else {
                            playerPositionMs()
                        }
                        danmakuController.onPlaying(position)
                        if (seek > 0) danmakuController.onSeek(seek)
                    }
                    current?.let { info ->
                        playbackHistoryCoordinator.onPlaying(
                            content = playbackHistoryContent(info),
                            positionMs = playerPositionMs(),
                            durationMs = playerDurationMs(),
                        )
                    }
                }
            }

            PlaybackEvent.Paused -> {
                LogHub.d(TAG, "ui paused")
                runOnUiThread {
                    ui = ui.copy(isPlaying = false)
                    playbackHistoryCoordinator.onPaused(playerPositionMs(), playerDurationMs())
                    if (current?.isLive == true) {
                        if (::liveDanmakuController.isInitialized) liveDanmakuController.onPaused()
                    } else if (::danmakuController.isInitialized) {
                        danmakuController.onPaused(
                            playerPositionMs(),
                        )
                    }
                }
            }

            PlaybackEvent.Stopped -> {
                LogHub.d(TAG, "ui stopped")
                runOnUiThread {
                    ui = ui.copy(isPlaying = false, isBuffering = false)
                    if (::danmakuController.isInitialized) danmakuController.close()
                    if (::liveDanmakuController.isInitialized) liveDanmakuController.close()
                }
            }

            PlaybackEvent.Ended -> {
                LogHub.i(TAG, "ui end reached")
                playbackHistoryCoordinator.finish("vlc-ended", ended = true)
                clearLiveSession("vlc-end-reached")
                castActive = false
                current = null
                runOnUiThread {
                    if (::danmakuController.isInitialized) danmakuController.close()
                    if (::liveDanmakuController.isInitialized) liveDanmakuController.close()
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

            is PlaybackEvent.Failed -> {
                val snapshot = runCatching { playbackEngineHost.stateSnapshot() }
                    .getOrDefault("(snapshot unavailable)")
                LogHub.e(TAG, "ui playback failed code=PLAYBACK_FAILED snapshot=$snapshot")
                playbackHistoryCoordinator.finish("vlc-error")
                val info = current
                if (info?.isLive == true) {
                    handleLivePlaybackFailure("player-error")
                    return
                }
                if (info != null && handleVodPlaybackFailure("player-error")) return
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
                if (::liveDanmakuController.isInitialized) liveDanmakuController.close()
            }

            is PlaybackEvent.Buffering -> {
                val buffering = event.isBuffering
                runOnUiThread {
                    ui = ui.copy(isBuffering = buffering)
                    if (current?.isLive == true &&
                        liveCoordinator.health.onBuffering(buffering)
                    ) {
                        handleLivePlaybackFailure("two-consecutive-rebuffers")
                    }
                    if (current?.isLive != true) observeVodBuffering(buffering)
                    if (current?.isLive != true && ::danmakuController.isInitialized) {
                        danmakuController.onBuffering(
                            buffering,
                            playerPositionMs(),
                        )
                    }
                }
            }

            is PlaybackEvent.VideoSizeChanged -> Unit
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
    private fun resolveNvaAndPlay(nva: NvaExtDecoder.NvaExtContent, title: String) =
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
    private fun clearLiveSession(reason: String) {
        val session = liveCoordinator.activeSession
        liveCoordinator.clear()
        val oldVodSession = vodCoordinator.activeSession
        vodCoordinator.clear()
        if (::liveDanmakuController.isInitialized) liveDanmakuController.close()
        if (session != null) LogHub.i(TAG, "live session cleared room=${session.stream.roomId} reason=$reason")
        if (oldVodSession != null) LogHub.i(TAG, "VOD session cleared reason=$reason")
    }


    /** 将播放器缓冲事件交给点播恢复状态机。 */
    private fun observeVodBuffering(buffering: Boolean) = vodCoordinator.observeBuffering(buffering)

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

    /** 使用指定播放信息打开播放器并更新界面状态。 */
    /** 使用指定播放信息打开播放器并更新界面状态。 */
    private fun play(info: BiliApi.PlayInfo, seekToMs: Long): Boolean {
        setCurrent(info)
        return castCoordinator.openPlayback(BiliPlaybackMapper.map(info), seekToMs)
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
        if (vodCoordinator.switching) return
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
        vodCoordinator.switching = true
        scope.launch {
            try {
                val mediaTrack = BiliPlaybackMapper.mapTrack(track)
                val routes = withContext(Dispatchers.IO) { vodCoordinator.cdn.rankTrack(mediaTrack) }
                if (current?.video?.id != info.video.id || !castActive) return@launch
                val route = routes.firstOrNull() ?: RankedVodRoute(track.url)
                val orderedRoutes = routes.ifEmpty { listOf(route) }
                val nextInfo = info.copy(video = track.copy(
                    url = route.url,
                    backup = orderedRoutes.filterNot { it.url == route.url }.map { it.url },
                    pcdn = CdnRouteRules.isPcdn(route.url),
                ))
                val oldSession = vodCoordinator.activeSession
                vodCoordinator.activeSession = VodSession(
                    request = BiliPlaybackMapper.map(nextInfo),
                    videoRoutes = orderedRoutes,
                    audioRoutes = oldSession?.audioRoutes.orEmpty(),
                    lastPositionMs = position,
                    lastProgressAtMs = SystemClock.elapsedRealtime(),
                )
                startVodBackupRefresh()
                if (play(nextInfo, position)) {
                    ui = ui.copy(
                        panel = PanelKind.QUALITY,
                        qualitySource = "手动",
                        toast = "已切换 ${nextInfo.qualityLabel}",
                    )
                }
            } catch (error: Throwable) {
                LogHub.e(TAG, "quality switch failed qn=$qn ${LogRedaction.reason(error)}", error)
                ui = ui.copy(toast = "画质切换失败")
            } finally {
                vodCoordinator.switching = false
            }
        }
    }

    /** 按用户选择重新解析并播放指定直播画质。 */
    private fun switchLiveQuality(qn: Int) {
        val session = liveCoordinator.activeSession ?: return
        if (!castActive) return
        if (qn == session.stream.quality) {
            ui = ui.copy(toast = "已是当前画质")
            return
        }
        if (liveCoordinator.switching) return
        liveCoordinator.switching = true
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
                    live to liveCoordinator.cdn.rankInitial(live.qualityCandidates)
                }
                if (liveCoordinator.activeSession !== session || !castActive) return@launch
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
                liveCoordinator.switching = false
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
        if (vodCoordinator.switching) return
        vodCoordinator.switching = true
        scope.launch {
            try {
                val mediaTrack = BiliPlaybackMapper.mapTrack(track)
                val routes = withContext(Dispatchers.IO) { vodCoordinator.cdn.rankTrack(mediaTrack) }
                if (current?.video?.id != info.video.id || !castActive) return@launch
                val route = routes.firstOrNull() ?: RankedVodRoute(track.url)
                val orderedRoutes = routes.ifEmpty { listOf(route) }
                val nextInfo = info.copy(audio = track.copy(
                    url = route.url,
                    backup = orderedRoutes.filterNot { it.url == route.url }.map { it.url },
                    pcdn = CdnRouteRules.isPcdn(route.url),
                ))
                vodCoordinator.activeSession?.let { session ->
                    session.request = BiliPlaybackMapper.map(nextInfo)
                    session.audioRoutes = orderedRoutes
                    session.attemptedAudioUrls.clear()
                }
                if (play(nextInfo, position)) {
                    ui = ui.copy(
                        panel = PanelKind.AUDIO,
                        toast = "已切换 ${BiliApi.audioLabelFor(id)}",
                    )
                }
            } catch (error: Throwable) {
                LogHub.e(TAG, "audio switch failed id=$id ${LogRedaction.reason(error)}", error)
                ui = ui.copy(toast = "音轨切换失败")
            } finally {
                vodCoordinator.switching = false
            }
        }
    }

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
    private fun refreshVideoSize() {
        if (playbackEngineHost.backend == PlaybackBackend.MEDIA3) return
        if (!::playbackEngineHost.isInitialized) return
        val size = playbackEngineHost.videoSize() ?: return
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
    /** 设置音量，沿用界面的 0..100 标度。 */
    private fun setVolume(volume: Int) = playbackCoordinator.setVolume(volume)

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
        playbackHistoryCoordinator.finish("dismiss-error")
        clearLiveSession("dismiss-error")
        if (::playbackEngineHost.isInitialized) {
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
        playbackHistoryCoordinator.finish("user-stop")
        clearLiveSession("user-stop")
        pendingPause = false
        pendingSeekMs = 0L
        if (::playbackEngineHost.isInitialized) {
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
        playbackHistoryCoordinator.finish("error:$code")
        val snapshot = if (::playbackEngineHost.isInitialized) {
            runCatching { playbackEngineHost.stateSnapshot() }.getOrDefault("(unavailable)")
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
            if (::playbackEngineHost.isInitialized) {
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
    private fun playerPositionMs(): Long = playbackEngineHost.snapshot(ui.volume).positionMs

    private fun playerDurationMs(): Long = playbackEngineHost.snapshot(ui.volume).durationMs

    private fun playerIsPlaying(): Boolean = playbackEngineHost.snapshot(ui.volume).isPlaying

    private fun playerVolume(): Int = playbackEngineHost.snapshot(ui.volume).volume

    private fun seekPlayer(positionMs: Long, reason: String): Long {
        if (current?.isLive != true) {
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
    private fun playbackHistoryContent(info: BiliApi.PlayInfo): PlaybackHistoryContent {
        val nva = lastNva?.takeIf { candidate ->
            if (info.isLive) {
                candidate.roomId > 0L && (info.cid <= 0L || candidate.roomId == info.cid)
            } else if (info.isPgc) {
                (info.epId <= 0L || candidate.eid <= 0L || candidate.eid == info.epId) &&
                    (info.seasonId <= 0L || candidate.sid <= 0L || candidate.sid == info.seasonId)
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

        val isPgc = info.isPgc || (nva != null && (nva.eid > 0L || nva.sid > 0L))
        val aid = info.aid.takeIf { it > 0L } ?: nva?.aid ?: 0L
        val cid = info.cid.takeIf { it > 0L } ?: nva?.cid ?: 0L
        val sid = if (isPgc) info.seasonId.takeIf { it > 0L } ?: nva?.sid ?: 0L else 0L
        val epid = if (isPgc) info.epId.takeIf { it > 0L } ?: nva?.eid ?: 0L else 0L
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
        if (current != null) {
            playbackHistoryCoordinator.onPosition(
                positionMs = playerPositionMs(),
                durationMs = playerDurationMs(),
                playing = playerIsPlaying(),
            )
        }
        playbackHistoryCoordinator.close()
        clearLiveSession("activity-destroy")
        PlaybackDiagnostics.closeSession("activity-destroyed")
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
