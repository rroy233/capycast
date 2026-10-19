package com.roy233.tvcast.platform.device

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * 提供 Android 设备与应用版本信息。
 *
 * @param context 用于访问系统服务和应用包信息的上下文。
 */
class DeviceInfoProvider(private val context: Context) {

    /** 保存设备身份兜底标识的偏好存储。 */
    private val identityPrefs = context.applicationContext.getSharedPreferences(
        IDENTITY_PREFS,
        Context.MODE_PRIVATE,
    )

    /** 获取当前连接的 Wi-Fi 名称，无法读取时返回通用名称。 */
    fun wifiName(): String = runCatching {
        val wm = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ssid = wm.connectionInfo?.ssid?.trim('"')
        if (ssid.isNullOrBlank() || ssid == "<unknown ssid>") "Wi-Fi" else ssid
    }.getOrDefault("Wi-Fi")

    /** 获取当前应用版本名，无法读取时返回默认版本。 */
    fun versionName(): String = runCatching {
        context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName ?: DEFAULT_VERSION_NAME
    }.getOrDefault(DEFAULT_VERSION_NAME)

    /**
     * 为投屏设备名称追加稳定的短设备标识。
     *
     * @param baseName 投屏设备名称基础部分。
     * @return 带有短设备标识的投屏设备名称。
     */
    fun castDeviceName(baseName: String): String {
        val name = baseName.trim().ifBlank { DEFAULT_DEVICE_NAME }
        return "$name-${shortDeviceId()}"
    }

    /** 生成用于区分同局域网设备的短标识。 */
    private fun shortDeviceId(): String {
        val androidId = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID,
            )
        }.getOrNull().orEmpty()
        val identity = androidId.ifBlank { persistedInstallId() }
        val fingerprint = listOf(
            Build.MANUFACTURER,
            Build.MODEL,
            Build.DEVICE,
            Build.PRODUCT,
            identity,
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(fingerprint.toByteArray(Charsets.UTF_8))
        return digest.take(SHORT_ID_BYTES)
            .joinToString("") { byte -> "%02X".format(Locale.US, byte.toInt() and 0xFF) }
    }

    /** 获取或创建无 Android ID 时使用的应用内安装标识。 */
    private fun persistedInstallId(): String =
        identityPrefs.getString(INSTALL_ID_KEY, null)
            ?: UUID.randomUUID().toString().also { id ->
                identityPrefs.edit().putString(INSTALL_ID_KEY, id).apply()
            }

    private companion object {
        /** 设备身份兜底标识的偏好文件名。 */
        const val IDENTITY_PREFS = "device_identity"
        /** 应用内持久化安装标识的键名。 */
        const val INSTALL_ID_KEY = "install_id"
        /** 设备短标识使用的摘要字节数。 */
        const val SHORT_ID_BYTES = 4
        /** 未提供设备名称时使用的默认名称。 */
        const val DEFAULT_DEVICE_NAME = "云视听小电视"
        /** 无法读取应用信息时使用的语义化版本号。 */
        const val DEFAULT_VERSION_NAME = "0.1.0"
    }
}
