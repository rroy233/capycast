package com.roy233.tvcast.platform.installer

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 校验已下载的 APK，并将其交给系统安装器处理。
 *
 * @param context 用于读取包信息和启动系统页面的上下文。
 */
class ApkUpdateInstaller(private val context: Context) {

    /**
     * 校验 APK 的摘要、包名和版本号。
     *
     * @param apk 待校验的 APK 文件。
     * @param sha256 发布清单声明的 SHA-256 校验值。
     * @param versionCode 发布清单声明的整数版本号。
     */
    suspend fun verify(apk: File, sha256: String, versionCode: Long): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(apk.isFile && apk.length() > 0L) { "更新安装包不存在" }
            require(sha256(apk).equals(sha256, ignoreCase = true)) { "更新安装包校验失败" }

            val packageInfo = context.packageManager.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.GET_ACTIVITIES,
            ) ?: error("无法读取更新安装包")
            require(packageInfo.packageName == context.packageName) { "更新安装包不属于当前应用" }
            require(PackageInfoCompat.getLongVersionCode(packageInfo) == versionCode) {
                "更新安装包版本号不匹配"
            }
        }
    }

    /** 返回当前应用是否已获得未知来源安装授权。 */
    fun canRequestPackageInstalls(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** 打开当前应用的未知来源安装授权页面。 */
    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** 启动系统安装器以覆盖安装已通过校验的 APK。 */
    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    }

    /** 计算文件的 SHA-256 十六进制摘要。 */
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private companion object {
        /** Android APK 文件的 MIME 类型。 */
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
