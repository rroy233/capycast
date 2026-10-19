package com.roy233.tvcast.data.local

import android.content.Context
import com.roy233.tvcast.feature.update.UpdateChannel

/**
 * 保存应用更新通道、自动检查开关和自动检查时间。
 *
 * @param context 用于访问应用偏好的上下文。
 */
class UpdatePreferences(context: Context) {

    /** 更新偏好的底层存储。 */
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 当前选择的更新通道。 */
    var channel: UpdateChannel
        get() = runCatching {
            UpdateChannel.valueOf(prefs.getString(KEY_CHANNEL, UpdateChannel.STABLE.name).orEmpty())
        }.getOrDefault(UpdateChannel.STABLE)
        set(value) = prefs.edit().putString(KEY_CHANNEL, value.name).apply()

    /** 是否在应用启动时执行自动更新检查。 */
    var automaticCheckEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CHECK, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CHECK, value).apply()

    /** 最近一次自动检查的完成时间，单位为毫秒。 */
    var lastAutomaticCheckAt: Long
        get() = prefs.getLong(KEY_LAST_AUTO_CHECK_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_AUTO_CHECK_AT, value).apply()

    private companion object {
        /** 偏好文件名。 */
        const val PREFS = "update_prefs"
        /** 更新通道字段名。 */
        const val KEY_CHANNEL = "channel"
        /** 自动检查开关字段名。 */
        const val KEY_AUTO_CHECK = "automatic_check"
        /** 最近自动检查时间字段名。 */
        const val KEY_LAST_AUTO_CHECK_AT = "last_automatic_check_at"
    }
}
