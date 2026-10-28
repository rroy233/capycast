package com.roy233.tvcast.feature.update

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import com.roy233.tvcast.BuildConfig
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.feature.update.data.UpdatePreferences
import com.roy233.tvcast.feature.update.data.ReleaseRepository
import com.roy233.tvcast.feature.update.model.UpdateChannel
import com.roy233.tvcast.platform.installer.ApkUpdateInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * 协调应用更新检查、安装包下载、完整性校验和系统安装流程。
 *
 * @param context 用于访问下载服务和应用私有文件目录的上下文。
 * @param scope 用于执行更新任务的界面生命周期协程作用域。
 * @param readUpdateState 读取更新功能独占的状态切片。
 * @param writeUpdateState 写入更新功能独占的状态切片。
 */
class UpdateCoordinator(
    context: Context,
    private val scope: CoroutineScope,
    private val readUpdateState: () -> UpdateUiState,
    private val writeUpdateState: (UpdateUiState) -> Unit,
    private val openSettingsNavigation: () -> Unit,
    private val closeSettingsNavigation: () -> Unit,
    private val openNotesNavigation: () -> Unit,
    private val closeNotesNavigation: () -> Unit,
    private val onToast: (String) -> Unit,
    private val preferences: UpdatePreferences = UpdatePreferences(context),
    private val repository: ReleaseRepository = ReleaseRepository(),
    private val installer: ApkUpdateInstaller = ApkUpdateInstaller(context),
) {

    /** 应用上下文，避免持有 Activity 实例。 */
    private val appContext = context.applicationContext
    /** 系统下载管理服务。 */
    private val downloadManager =
        appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    /** 当前下载的轮询任务。 */
    private var downloadJob: Job? = null
    /** 当前下载任务的系统标识。 */
    private var downloadId: Long? = null

    /** 使用已保存的更新偏好初始化界面状态。 */
    fun initialize() {
        setUpdateState {
            it.copy(
                channel = preferences.channel,
                automaticCheck = preferences.automaticCheckEnabled,
            )
        }
    }

    /** 打开应用更新二级页面。 */
    fun openSettings() {
        openSettingsNavigation()
    }

    /** 返回设置主页。 */
    fun closeSettings() {
        closeSettingsNavigation()
    }

    /** 打开当前可用更新的更新日志页面。 */
    fun openNotes() {
        if (readUpdateState().availableUpdate == null) return
        openNotesNavigation()
    }

    /** 从更新日志返回应用更新页面。 */
    fun closeNotes() {
        closeNotesNavigation()
    }

    /** 切换更新发布通道并清除当前查询结果。 */
    fun selectChannel(channel: UpdateChannel) {
        if (channel == readUpdateState().channel) return
        LogHub.i(TAG, "channel selected=${channel.apiValue}")
        cancelDownload(removeTask = true)
        preferences.channel = channel
        setUpdateState {
            it.copy(
                channel = channel,
                status = UpdateStatus.IDLE,
                availableUpdate = null,
                downloadedApkPath = null,
                downloadedBytes = 0L,
                totalBytes = 0L,
                message = null,
            )
        }
    }

    /** 更新自动检查开关。 */
    fun setAutomaticCheck(enabled: Boolean) {
        preferences.automaticCheckEnabled = enabled
        setUpdateState { it.copy(automaticCheck = enabled) }
    }

    /** 在应用启动时按用户设置检查更新。 */
    fun checkAutomaticallyOnLaunch() {
        if (!preferences.automaticCheckEnabled) {
            LogHub.i(TAG, "auto check skipped: disabled")
            return
        }
        checkForUpdates(manual = false)
    }

    /** 查询当前通道是否存在可用更新。 */
    fun checkForUpdates(manual: Boolean = true) {
        if (readUpdateState().status == UpdateStatus.CHECKING) return
        val channel = readUpdateState().channel
        LogHub.i(
            TAG,
            "check requested channel=${channel.apiValue} manual=$manual " +
                "currentVersion=${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE}",
        )
        setUpdateState {
            it.copy(
                status = UpdateStatus.CHECKING,
                availableUpdate = null,
                downloadedApkPath = null,
                downloadedBytes = 0L,
                totalBytes = 0L,
                message = if (manual) "正在检查更新…" else null,
            )
        }
        scope.launch {
            runCatching { repository.fetchLatest(channel) }
                .onSuccess { update ->
                    when {
                        update == null -> {
                            LogHub.i(TAG, "check complete no release channel=${channel.apiValue}")
                            showNoUpdate(manual)
                        }
                        update.versionCode <= BuildConfig.VERSION_CODE.toLong() -> {
                            LogHub.i(TAG, "check complete up-to-date latest=${update.versionName}")
                            showNoUpdate(manual)
                        }
                        else -> {
                            LogHub.i(
                                TAG,
                                "check complete available version=${update.versionName} " +
                                    "code=${update.versionCode} size=${update.sizeBytes}",
                            )
                            showAvailableUpdate(update, manual)
                        }
                    }
                }
                .onFailure { error ->
                    LogHub.e(TAG, "check failed channel=${channel.apiValue}", error)
                    setUpdateState {
                        it.copy(
                            status = UpdateStatus.FAILED,
                            message = error.message ?: "检查更新失败，请稍后重试。",
                        )
                    }
                }
        }
    }

    /** 下载当前可用更新，或复用已下载且已校验的缓存文件。 */
    fun downloadUpdate() {
        val update = readUpdateState().availableUpdate ?: return
        val cacheFile = cacheFile(update)
        scope.launch {
            LogHub.i(TAG, "download requested version=${update.versionName}")
            setUpdateState { it.copy(status = UpdateStatus.VERIFYING, message = "正在检查已下载的安装包…") }
            val cachedVerification = installer.verify(cacheFile, update.sha256, update.versionCode)
            if (cachedVerification.isSuccess) {
                LogHub.i(TAG, "verified cached apk version=${update.versionName} bytes=${cacheFile.length()}")
                setReady(update, cacheFile, "已复用已验证的安装包")
                return@launch
            }
            if (cacheFile.exists()) {
                LogHub.i(TAG, "cached apk rejected version=${update.versionName}: ${cachedVerification.exceptionOrNull()?.message}")
            }
            if (cacheFile.exists()) cacheFile.delete()
            enqueueDownload(update, cacheFile)
        }
    }

    /** 取消当前下载任务。 */
    fun cancelUpdateDownload() {
        cancelDownload(removeTask = true)
        setUpdateState {
            it.copy(
                status = if (it.availableUpdate == null) UpdateStatus.IDLE else UpdateStatus.AVAILABLE,
                downloadedBytes = 0L,
                totalBytes = 0L,
                message = "下载已取消",
            )
        }
    }

    /** 请求安装已完成并校验的 APK。 */
    fun installUpdate() {
        val updateState = readUpdateState()
        val apk = updateState.downloadedApkPath?.let(::File) ?: return
        val update = updateState.availableUpdate ?: return
        scope.launch {
            setUpdateState { it.copy(status = UpdateStatus.VERIFYING, message = "正在再次校验安装包…") }
            installer.verify(apk, update.sha256, update.versionCode)
                .onSuccess {
                    if (installer.canRequestPackageInstalls()) {
                        setReady(update, apk, "正在打开系统安装器…")
                        installer.install(apk)
                    } else {
                        setReady(update, apk, "请允许此应用安装未知来源应用")
                        installer.openInstallPermissionSettings()
                    }
                }
                .onFailure { error ->
                    setUpdateState {
                        it.copy(
                            status = UpdateStatus.FAILED,
                            downloadedApkPath = null,
                            message = error.message ?: "安装包校验失败，请重新下载。",
                        )
                    }
                }
        }
    }

    /** 取消协程轮询任务，并按需取消系统下载任务。 */
    fun cancel() {
        cancelDownload(removeTask = false)
    }

    /** 显示当前通道没有可用更新的状态。 */
    private fun showNoUpdate(manual: Boolean) {
        if (!manual) showToast("已是最新版本")
        setUpdateState {
            it.copy(
                status = UpdateStatus.UP_TO_DATE,
                message = if (manual) "当前已是最新版本" else null,
            )
        }
    }

    /** 显示可下载更新，并优先使用有效缓存。 */
    private fun showAvailableUpdate(update: AppUpdateInfo, manual: Boolean) {
        if (!manual) showToast("发现新版本 ${update.versionName}")
        scope.launch {
            val cacheFile = cacheFile(update)
            if (installer.verify(cacheFile, update.sha256, update.versionCode).isSuccess) {
                setReady(update, cacheFile, if (manual) "发现新版本，安装包已就绪" else "发现新版本")
            } else {
                setUpdateState {
                    it.copy(
                        status = UpdateStatus.AVAILABLE,
                        availableUpdate = update,
                        downloadedApkPath = null,
                        downloadedBytes = 0L,
                        totalBytes = update.sizeBytes,
                        message = "发现新版本 ${update.versionName}",
                    )
                }
            }
        }
    }

    /** 创建系统下载任务并开始轮询下载进度。 */
    private fun enqueueDownload(
        update: AppUpdateInfo,
        cacheFile: File,
        downloadUrl: String = update.downloadUrl,
    ) {
        val source = if (downloadUrl == update.fallbackDownloadUrl) "proxy" else "github"
        LogHub.i(TAG, "enqueue download source=$source version=${update.versionName}")
        val request = DownloadManager.Request(Uri.parse(downloadUrl))
            .setTitle("CapyCast ${update.versionName}")
            .setDescription("正在下载应用更新")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(
                appContext,
                Environment.DIRECTORY_DOWNLOADS,
                "updates/${cacheFile.name}",
            )
        val id = downloadManager.enqueue(request)
        downloadId = id
        LogHub.i(TAG, "download enqueued id=$id source=$source")
        setUpdateState {
            it.copy(
                status = UpdateStatus.DOWNLOADING,
                downloadedBytes = 0L,
                totalBytes = update.sizeBytes,
                message = "正在下载 ${update.versionName}",
            )
        }
        downloadJob?.cancel()
        downloadJob = scope.launch { pollDownload(update, cacheFile, id, downloadUrl) }
    }

    /** 轮询系统下载任务并在完成后校验 APK。 */
    private suspend fun pollDownload(
        update: AppUpdateInfo,
        cacheFile: File,
        id: Long,
        downloadUrl: String,
    ) {
        val source = if (downloadUrl == update.fallbackDownloadUrl) "proxy" else "github"
        var loggedProgressBucket = -1
        var lastDownloadedBytes = -1L
        var lastProgressAt = SystemClock.elapsedRealtime()
        while (currentCoroutineContext().isActive) {
            val snapshot = try {
                queryDownload(id)
            } catch (error: Exception) {
                LogHub.e(TAG, "download status query failed id=$id source=$source", error)
                setUpdateState {
                    it.copy(status = UpdateStatus.FAILED, message = "无法读取下载状态，请重试。")
                }
                return
            }
            when (snapshot.status) {
                DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_PAUSED,
                DownloadManager.STATUS_RUNNING -> {
                    if (snapshot.downloadedBytes != lastDownloadedBytes) {
                        lastDownloadedBytes = snapshot.downloadedBytes
                        lastProgressAt = SystemClock.elapsedRealtime()
                    } else if (source == "github" &&
                        update.fallbackDownloadUrl != null &&
                        SystemClock.elapsedRealtime() - lastProgressAt >= DIRECT_STALL_TIMEOUT_MS
                    ) {
                        LogHub.i(TAG, "download stalled id=$id; switching from github to proxy")
                        downloadManager.remove(id)
                        downloadId = null
                        cacheFile.delete()
                        setUpdateState {
                            it.copy(
                                status = UpdateStatus.DOWNLOADING,
                                downloadedBytes = 0L,
                                totalBytes = update.sizeBytes,
                                message = "直连下载较慢，正在切换备用源…",
                            )
                        }
                        enqueueDownload(update, cacheFile, update.fallbackDownloadUrl)
                        return
                    }
                    setUpdateState {
                        it.copy(
                            status = UpdateStatus.DOWNLOADING,
                            downloadedBytes = snapshot.downloadedBytes,
                            totalBytes = snapshot.totalBytes.takeIf { size -> size > 0L } ?: update.sizeBytes,
                        )
                    }
                    if (snapshot.totalBytes > 0L) {
                        val bucket = (snapshot.downloadedBytes * 100L / snapshot.totalBytes / 10L).toInt()
                        if (bucket > loggedProgressBucket) {
                            loggedProgressBucket = bucket
                            LogHub.i(
                                TAG,
                                "download progress id=$id source=$source " +
                                    "percent=${(snapshot.downloadedBytes * 100L / snapshot.totalBytes).coerceIn(0L, 100L)} " +
                                    "bytes=${snapshot.downloadedBytes}/${snapshot.totalBytes}",
                            )
                        }
                    }
                    delay(DOWNLOAD_POLL_INTERVAL_MS)
                }

                DownloadManager.STATUS_SUCCESSFUL -> {
                    downloadId = null
                    LogHub.i(TAG, "download complete id=$id source=$source bytes=${cacheFile.length()}")
                    setUpdateState { it.copy(status = UpdateStatus.VERIFYING, message = "正在校验安装包…") }
                    installer.verify(cacheFile, update.sha256, update.versionCode)
                        .onSuccess {
                            LogHub.i(TAG, "apk verification passed version=${update.versionName}")
                            setReady(update, cacheFile, "安装包校验完成")
                        }
                        .onFailure { error ->
                            LogHub.e(TAG, "apk verification failed version=${update.versionName}", error)
                            cacheFile.delete()
                            setUpdateState {
                                it.copy(
                                    status = UpdateStatus.FAILED,
                                    downloadedApkPath = null,
                                    message = error.message ?: "安装包校验失败，请重新下载。",
                                )
                            }
                        }
                    return
                }

                else -> {
                    downloadId = null
                    val fallbackUrl = update.fallbackDownloadUrl
                        ?.takeIf { it != downloadUrl }
                    if (fallbackUrl != null) {
                        LogHub.e(
                            TAG,
                            "download failed id=$id source=$source reason=${snapshot.reason}; retrying proxy",
                        )
                        setUpdateState {
                            it.copy(
                                status = UpdateStatus.DOWNLOADING,
                                downloadedBytes = 0L,
                                totalBytes = update.sizeBytes,
                                message = "直连下载失败，正在切换备用源…",
                            )
                        }
                        cacheFile.delete()
                        enqueueDownload(update, cacheFile, fallbackUrl)
                        return
                    }
                    LogHub.e(TAG, "download failed id=$id source=$source reason=${snapshot.reason}")
                    setUpdateState {
                        it.copy(
                            status = UpdateStatus.FAILED,
                            message = "下载失败（系统原因 ${snapshot.reason}）",
                        )
                    }
                    return
                }
            }
        }
    }

    /** 查询下载任务的进度和状态。 */
    private fun queryDownload(id: Long): DownloadSnapshot {
        val query = DownloadManager.Query().setFilterById(id)
        downloadManager.query(query).use { cursor ->
            if (!cursor.moveToFirst()) {
                return DownloadSnapshot(DownloadManager.STATUS_FAILED, 0L, 0L, 0)
            }
            return DownloadSnapshot(
                status = cursor.int(DownloadManager.COLUMN_STATUS),
                downloadedBytes = cursor.long(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                totalBytes = cursor.long(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                reason = cursor.int(DownloadManager.COLUMN_REASON),
            )
        }
    }

    /** 切换到可安装状态。 */
    private fun setReady(update: AppUpdateInfo, apk: File, message: String) {
        setUpdateState {
            it.copy(
                status = UpdateStatus.READY_TO_INSTALL,
                availableUpdate = update,
                downloadedApkPath = apk.absolutePath,
                downloadedBytes = apk.length(),
                totalBytes = update.sizeBytes,
                message = message,
            )
        }
    }

    /** 返回指定更新在应用专属下载目录中的缓存文件。 */
    private fun cacheFile(update: AppUpdateInfo): File {
        val directory = File(
            requireNotNull(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)),
            "updates",
        )
        return File(directory, "${update.versionCode}-${update.sha256.take(CACHE_HASH_LENGTH)}.apk")
    }

    /** 停止下载轮询，并按需移除系统下载任务。 */
    private fun cancelDownload(removeTask: Boolean) {
        downloadJob?.cancel()
        downloadJob = null
        if (removeTask) {
            downloadId?.let { id -> downloadManager.remove(id) }
            downloadId = null
        }
    }

    /** 更新 TV 根状态中的更新子状态。 */
    private fun setUpdateState(transform: (UpdateUiState) -> UpdateUiState) {
        writeUpdateState(transform(readUpdateState()))
    }

    /** 在界面底部显示短暂提示，复用全局提示组件。 */
    private fun showToast(message: String) {
        onToast(message)
    }

    /** 下载任务状态的快照。 */
    private data class DownloadSnapshot(
        val status: Int,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val reason: Int,
    )

    private companion object {
        /** 更新流程日志标签。 */
        const val TAG = "UpdateCoordinator"
        /** 下载进度轮询间隔。 */
        const val DOWNLOAD_POLL_INTERVAL_MS = 350L
        /** 直连下载无进度达到该时长后切换到备用代理。 */
        const val DIRECT_STALL_TIMEOUT_MS = 30_000L
        /** 缓存文件名使用的 SHA-256 前缀长度。 */
        const val CACHE_HASH_LENGTH = 16
    }
}

/** 读取游标中的整数列。 */
private fun Cursor.int(columnName: String): Int = getInt(getColumnIndexOrThrow(columnName))

/** 读取游标中的长整数列。 */
private fun Cursor.long(columnName: String): Long = getLong(getColumnIndexOrThrow(columnName))
