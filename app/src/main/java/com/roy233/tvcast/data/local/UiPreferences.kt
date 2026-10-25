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
        get() = prefs.getString("device_name", "CapyCast") ?: "CapyCast"

    /** 配置的房间名称，未配置时使用默认名称。 */
    val roomName: String
        get() = prefs.getString("room_name", "客厅") ?: "客厅"

    /** 是否保留最近一次投屏内容，默认开启以兼容已有使用习惯。 */
    var rememberPlaybackContent: Boolean
        get() = prefs.getBoolean(KEY_REMEMBER_PLAYBACK_CONTENT, true)
        set(value) {
            prefs.edit().putBoolean(KEY_REMEMBER_PLAYBACK_CONTENT, value).apply()
        }

    /** 是否将投屏历史与进度同步到 B 站账号，默认开启。 */
    var syncPlaybackHistory: Boolean
        get() = prefs.getBoolean(KEY_SYNC_PLAYBACK_HISTORY, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SYNC_PLAYBACK_HISTORY, value).apply()
        }

    private companion object {
        /** 偏好文件名。 */
        const val PREFS = "ui_prefs"
        /** 是否记忆投屏内容的偏好键。 */
        const val KEY_REMEMBER_PLAYBACK_CONTENT = "remember_playback_content"
        /** 是否将投屏播放记录同步到 B 站的偏好键。 */
        const val KEY_SYNC_PLAYBACK_HISTORY = "sync_playback_history"
    }
}
