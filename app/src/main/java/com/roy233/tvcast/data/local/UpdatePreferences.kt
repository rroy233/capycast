package com.roy233.tvcast.data.local

import android.content.Context
import com.roy233.tvcast.feature.update.model.UpdateChannel

/**
 * 保存应用更新通道和自动检查开关。
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

    private companion object {
        /** 偏好文件名。 */
        const val PREFS = "update_prefs"
        /** 更新通道字段名。 */
        const val KEY_CHANNEL = "channel"
        /** 自动检查开关字段名。 */
        const val KEY_AUTO_CHECK = "automatic_check"
    }
}
