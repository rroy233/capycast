package com.roy233.tvcast.feature.update

/** 发布通道由数据层定义，此处保留同名别名供更新功能使用。 */
typealias UpdateChannel = com.roy233.tvcast.data.local.UpdateChannel

/** 表示应用更新流程当前所处的阶段。 */
enum class UpdateStatus {
    /** 尚未执行更新操作。 */
    IDLE,

    /** 正在从版本服务查询发布信息。 */
    CHECKING,

    /** 当前通道没有更高版本。 */
    UP_TO_DATE,

    /** 已发现可下载的更新。 */
    AVAILABLE,

    /** 正在下载更新安装包。 */
    DOWNLOADING,

    /** 正在校验安装包完整性。 */
    VERIFYING,

    /** 已获得可安装的更新安装包。 */
    READY_TO_INSTALL,

    /** 查询、下载或校验过程中发生错误。 */
    FAILED,
}

/**
 * 描述版本服务返回的可安装应用更新。
 *
 * @property channel 发布所属通道。
 * @property versionName 用户可见的版本号。
 * @property versionCode Android 用于比较新旧版本的整数版本号。
 * @property apkName 发布附件中的 APK 文件名。
 * @property downloadUrl APK 的公开下载地址。
 * @property fallbackDownloadUrl APK 的备用代理下载地址。
 * @property sha256 APK 的 SHA-256 校验值。
 * @property sizeBytes APK 文件大小，单位为字节。
 * @property releaseNotes 发布说明的 Markdown 内容。
 * @property releaseUrl 发布页面地址。
 */
data class AppUpdateInfo(
    val channel: UpdateChannel,
    val versionName: String,
    val versionCode: Long,
    val apkName: String,
    val downloadUrl: String,
    val fallbackDownloadUrl: String? = null,
    val sha256: String,
    val sizeBytes: Long,
    val releaseNotes: String,
    val releaseUrl: String,
)

/**
 * 保存更新二级页面所需的不可变界面状态。
 *
 * @property channel 当前选择的更新通道。
 * @property automaticCheck 是否在应用启动时自动检查更新。
 * @property status 当前更新流程状态。
 * @property availableUpdate 当前查询到的更新信息。
 * @property downloadedApkPath 已完成下载并通过校验的 APK 路径。
 * @property downloadedBytes 当前已下载字节数。
 * @property totalBytes 当前下载任务的总字节数。
 * @property message 面向用户展示的状态说明或错误信息。
 */
data class UpdateUiState(
    val channel: UpdateChannel = UpdateChannel.STABLE,
    val automaticCheck: Boolean = true,
    val status: UpdateStatus = UpdateStatus.IDLE,
    val availableUpdate: AppUpdateInfo? = null,
    val downloadedApkPath: String? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val message: String? = null,
)

/**
 * 描述发布附件 `capycast-update.json` 的受校验内容。
 *
 * @property schemaVersion 清单格式版本。
 * @property channel 发布通道名称。
 * @property versionName 用户可见的版本号。
 * @property versionCode Android 整数版本号。
 * @property apkName APK 发布附件名称。
 * @property sha256 APK 的 SHA-256 校验值。
 * @property sizeBytes APK 大小，单位为字节。
 * @property minSdk APK 支持的最低 Android API 级别。
 */
internal data class UpdateManifest(
    val schemaVersion: Int,
    val channel: String,
    val versionName: String,
    val versionCode: Long,
    val apkName: String,
    val sha256: String,
    val sizeBytes: Long,
    val minSdk: Int,
)
