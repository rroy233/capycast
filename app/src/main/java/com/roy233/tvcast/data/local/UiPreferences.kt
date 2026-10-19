package com.roy233.tvcast.data.local

import android.content.Context

/**
 * 读取电视设备身份相关的界面偏好。
 *
 * @param context 用于访问应用偏好的上下文。
 */
class UiPreferences(context: Context) {

    /** 保存界面偏好的存储。 */
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 配置的设备名称，未配置时使用默认名称。 */
    val deviceName: String
        get() = prefs.getString("device_name", "云视听小电视") ?: "云视听小电视"

    /** 配置的房间名称，未配置时使用默认名称。 */
    val roomName: String
        get() = prefs.getString("room_name", "客厅") ?: "客厅"

    private companion object {
        /** 偏好文件名。 */
        const val PREFS = "ui_prefs"
    }
}
