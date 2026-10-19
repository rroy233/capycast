package com.roy233.tvcast.feature.update

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import com.roy233.tvcast.BuildConfig
import com.roy233.tvcast.data.local.UpdatePreferences
import com.roy233.tvcast.data.update.GitHubReleaseRepository
import com.roy233.tvcast.platform.installer.ApkUpdateInstaller
import com.roy233.tvcast.ui.model.TvUiState
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
 * @param readState 读取当前电视界面状态的函数。
 * @param writeState 写入最新电视界面状态的函数。
 */
class UpdateCoordinator(
    context: Context,
    private val scope: CoroutineScope,
    private val readState: () -> TvUiState,
    private val writeState: (TvUiState) -> Unit,
    private val preferences: UpdatePreferences = UpdatePreferences(context),
    private val repository: GitHubReleaseRepository = GitHubReleaseRepository(),
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
        val state = readState()
        writeState(
            state.copy(
                destination = com.roy233.tvcast.ui.model.TvDestination.SETTINGS,
                settingsPage = com.roy233.tvcast.ui.model.SettingsPage.UPDATE,
                sidebarVisible = false,
                hudVisible = false,
            )
        )
    }

    /** 返回设置主页。 */
    fun closeSettings() {
        val state = readState()
        writeState(state.copy(settingsPage = com.roy233.tvcast.ui.model.SettingsPage.ROOT))
    }

    /** 切换更新发布通道并清除当前查询结果。 */
    fun selectChannel(channel: UpdateChannel) {
        if (channel == readState().update.channel) return
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

    /** 在距离上次自动检查至少一天时执行后台更新检查。 */
    fun checkAutomaticallyIfDue() {
        val dueAt = preferences.lastAutomaticCheckAt + AUTO_CHECK_INTERVAL_MS
        if (preferences.automaticCheckEnabled && System.currentTimeMillis() >= dueAt) {
            checkForUpdates(manual = false)
        }
    }

    /** 查询当前通道是否存在可用更新。 */
    fun checkForUpdates(manual: Boolean = true) {
        if (readState().update.status == UpdateStatus.CHECKING) return
        val channel = readState().update.channel
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
                    if (!manual) preferences.lastAutomaticCheckAt = System.currentTimeMillis()
                    when {
                        update == null -> showNoUpdate(manual)
                        update.versionCode <= BuildConfig.VERSION_CODE.toLong() -> showNoUpdate(manual)
                        else -> showAvailableUpdate(update, manual)
                    }
                }
                .onFailure { error ->
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
        val update = readState().update.availableUpdate ?: return
        val cacheFile = cacheFile(update)
        scope.launch {
            setUpdateState { it.copy(status = UpdateStatus.VERIFYING, message = "正在检查已下载的安装包…") }
            if (installer.verify(cacheFile, update).isSuccess) {
                setReady(update, cacheFile, "已复用已验证的安装包")
                return@launch
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
        val updateState = readState().update
        val apk = updateState.downloadedApkPath?.let(::File) ?: return
        val update = updateState.availableUpdate ?: return
        scope.launch {
            setUpdateState { it.copy(status = UpdateStatus.VERIFYING, message = "正在再次校验安装包…") }
            installer.verify(apk, update)
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
        setUpdateState {
            it.copy(
                status = UpdateStatus.UP_TO_DATE,
                message = if (manual) "当前已是最新版本" else null,
            )
        }
    }

    /** 显示可下载更新，并优先使用有效缓存。 */
    private fun showAvailableUpdate(update: AppUpdateInfo, manual: Boolean) {
        scope.launch {
            val cacheFile = cacheFile(update)
            if (installer.verify(cacheFile, update).isSuccess) {
                setReady(update, cacheFile, if (manual) "发现新版本，安装包已就绪" else "发现新版本")
            } else {
                setUpdateState {
                    it.copy(
                        status = UpdateStatus.AVAILABLE,
                        availableUpdate = update,
                        downloadedApkPath = null,
                        downloadedBytes = 0L,
                        totalBytes = update.sizeBytes,
                        message = if (manual) "发现新版本 ${update.versionName}" else "发现新版本 ${update.versionName}",
                    )
                }
            }
        }
    }

    /** 创建系统下载任务并开始轮询下载进度。 */
    private fun enqueueDownload(update: AppUpdateInfo, cacheFile: File) {
        val request = DownloadManager.Request(Uri.parse(update.downloadUrl))
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
        downloadId = downloadManager.enqueue(request)
        setUpdateState {
            it.copy(
                status = UpdateStatus.DOWNLOADING,
                downloadedBytes = 0L,
                totalBytes = update.sizeBytes,
                message = "正在下载 ${update.versionName}",
            )
        }
        downloadJob?.cancel()
        downloadJob = scope.launch { pollDownload(update, cacheFile, requireNotNull(downloadId)) }
    }

    /** 轮询系统下载任务并在完成后校验 APK。 */
    private suspend fun pollDownload(update: AppUpdateInfo, cacheFile: File, id: Long) {
        while (currentCoroutineContext().isActive) {
            val snapshot = queryDownload(id)
            when (snapshot.status) {
                DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_PAUSED,
                DownloadManager.STATUS_RUNNING -> {
                    setUpdateState {
                        it.copy(
                            status = UpdateStatus.DOWNLOADING,
                            downloadedBytes = snapshot.downloadedBytes,
                            totalBytes = snapshot.totalBytes.takeIf { size -> size > 0L } ?: update.sizeBytes,
                        )
                    }
                    delay(DOWNLOAD_POLL_INTERVAL_MS)
                }

                DownloadManager.STATUS_SUCCESSFUL -> {
                    downloadId = null
                    setUpdateState { it.copy(status = UpdateStatus.VERIFYING, message = "正在校验安装包…") }
                    installer.verify(cacheFile, update)
                        .onSuccess { setReady(update, cacheFile, "安装包校验完成") }
                        .onFailure { error ->
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
        val state = readState()
        writeState(state.copy(update = transform(state.update)))
    }

    /** 下载任务状态的快照。 */
    private data class DownloadSnapshot(
        val status: Int,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val reason: Int,
    )

    private companion object {
        /** 自动检查间隔为 24 小时。 */
        const val AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
        /** 下载进度轮询间隔。 */
        const val DOWNLOAD_POLL_INTERVAL_MS = 350L
        /** 缓存文件名使用的 SHA-256 前缀长度。 */
        const val CACHE_HASH_LENGTH = 16
    }
}

/** 读取游标中的整数列。 */
private fun Cursor.int(columnName: String): Int = getInt(getColumnIndexOrThrow(columnName))

/** 读取游标中的长整数列。 */
private fun Cursor.long(columnName: String): Long = getLong(getColumnIndexOrThrow(columnName))
