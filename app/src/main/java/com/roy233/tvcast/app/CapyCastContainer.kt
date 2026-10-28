package com.roy233.tvcast.app

import android.content.Context
import com.roy233.tvcast.data.auth.AuthTokenStore
import com.roy233.tvcast.data.auth.CastAuth
import com.roy233.tvcast.data.bili.BiliPlaybackResolver
import com.roy233.tvcast.data.bili.BiliLivePlaybackResolver
import com.roy233.tvcast.data.bili.BiliAccountNavigator
import com.roy233.tvcast.feature.playback.model.LivePlaybackResolver
import com.roy233.tvcast.data.local.DanmakuPreferences
import com.roy233.tvcast.data.local.PlaybackHistoryOutbox
import com.roy233.tvcast.data.local.RecentCastStore
import com.roy233.tvcast.data.local.UiPreferences
import com.roy233.tvcast.feature.account.AccountLoginCoordinator
import com.roy233.tvcast.feature.account.AccountUiState
import com.roy233.tvcast.feature.history.PlaybackHistoryCoordinator
import com.roy233.tvcast.core.playback.PlaybackResolver
import com.roy233.tvcast.feature.update.UpdateCoordinator
import com.roy233.tvcast.feature.update.UpdateUiState
import com.roy233.tvcast.platform.device.DeviceInfoProvider
import com.roy233.tvcast.platform.qr.QrCodeGenerator
import kotlinx.coroutines.CoroutineScope

/**
 * 应用依赖组合根。
 *
 * 集中创建投屏、账号、更新、历史与本地偏好等协作对象，让 `MainActivity` 只负责
 * Android 宿主生命周期与界面装配。各协作对象在首次访问时惰性创建。
 */
class CapyCastContainer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val readAccountState: () -> AccountUiState,
    private val writeAccountState: (AccountUiState) -> Unit,
    private val readUpdateState: () -> UpdateUiState,
    private val writeUpdateState: (UpdateUiState) -> Unit,
    private val onLogoutUi: () -> Unit,
    private val onToast: (String) -> Unit,
    private val openUpdateSettingsNavigation: () -> Unit,
    private val closeUpdateSettingsNavigation: () -> Unit,
    private val openUpdateNotesNavigation: () -> Unit,
    private val closeUpdateNotesNavigation: () -> Unit,
    private val isPlaying: () -> Boolean,
    private val positionMs: () -> Long,
    private val durationMs: () -> Long,
) {

    /** 设备身份偏好读取器。 */
    val uiPreferences: UiPreferences by lazy { UiPreferences(context) }

    /** 弹幕设置读取器。 */
    val danmakuPreferences: DanmakuPreferences by lazy { DanmakuPreferences(context) }

    /** 最近投屏记录存储。 */
    val recentCastStore: RecentCastStore by lazy { RecentCastStore(context) }

    /** TV 登录令牌存储；直播协议需要其中的 access_key。 */
    val authTokenStore: AuthTokenStore by lazy { AuthTokenStore(context) }

    /** 将 B 站协议响应适配为播放器无关的请求。 */
    val playbackResolver: PlaybackResolver by lazy { BiliPlaybackResolver(authTokenStore) }
    val livePlaybackResolver: LivePlaybackResolver by lazy { BiliLivePlaybackResolver(context) }

    /** 设备信息读取器。 */
    val deviceInfo: DeviceInfoProvider by lazy { DeviceInfoProvider(context) }

    /** 对外显示并广播的投屏设备名称。 */
    val castDeviceName: String by lazy {
        deviceInfo.castDeviceName(uiPreferences.deviceName)
    }

    /** 将投屏进度与直播心跳同步到 B 站账号。 */
    val playbackHistoryCoordinator: PlaybackHistoryCoordinator by lazy {
        PlaybackHistoryCoordinator(
            scope = scope,
            sessionStore = authTokenStore,
            outbox = PlaybackHistoryOutbox(context),
            isEnabled = { uiPreferences.syncPlaybackHistory },
            buildNumber = CastAuth.tvBuildNumber,
        )
    }

    /** 账号二维码登录协调器。 */
    val loginCoordinator: AccountLoginCoordinator by lazy {
        AccountLoginCoordinator(
            context = context,
            scope = scope,
            readState = readAccountState,
            writeState = writeAccountState,
            sessionStore = authTokenStore,
            authService = CastAuth,
            accountNavigator = BiliAccountNavigator(authTokenStore),
            qrCodeGenerator = QrCodeGenerator(),
            onTokenAvailable = {
                playbackHistoryCoordinator.onAccountAvailable(it.mid)
            },
            onLogout = {
                playbackHistoryCoordinator.onAccountLoggedOut(
                    playing = isPlaying(),
                    positionMs = positionMs(),
                    durationMs = durationMs(),
                )
            },
            onLogoutUi = onLogoutUi,
            onToast = onToast,
        )
    }

    /** 应用更新流程协调器。 */
    val updateCoordinator: UpdateCoordinator by lazy {
        UpdateCoordinator(
            context = context,
            scope = scope,
            readUpdateState = readUpdateState,
            writeUpdateState = writeUpdateState,
            openSettingsNavigation = openUpdateSettingsNavigation,
            closeSettingsNavigation = closeUpdateSettingsNavigation,
            openNotesNavigation = openUpdateNotesNavigation,
            closeNotesNavigation = closeUpdateNotesNavigation,
            onToast = onToast,
        )
    }
}
