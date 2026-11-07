package com.roy233.tvcast.data.local

import android.content.Context
import com.roy233.tvcast.feature.subtitle.domain.SubtitleSettings

/**
 * 字幕显示设置持久化。
 *
 * 只保存显示偏好，不保存具体视频的轨道选择。
 *
 * @param context 用于访问应用偏好的上下文。
 */
class SubtitlePreferences(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 读取已保存的设置，非法值回退到默认值。 */
    fun load(): SubtitleSettings =
        SubtitleSettings(
                enabled = prefs.getBoolean(KEY_ENABLED, true),
                autoAiEnabled = prefs.getBoolean(KEY_AUTO_AI, false),
                fontScale = prefs.getFloat(KEY_FONT_SCALE, 1f),
                bottomMarginRatio =
                    prefs.getFloat(
                        KEY_BOTTOM_MARGIN,
                        SubtitleSettings.DEFAULT_BOTTOM_MARGIN,
                    ),
                offsetMs = prefs.getLong(KEY_OFFSET_MS, 0L),
            )
            .sanitized()

    /** 保存设置。 */
    fun save(settings: SubtitleSettings) {
        val safe = settings.sanitized()
        prefs
            .edit()
            .putBoolean(KEY_ENABLED, safe.enabled)
            .putBoolean(KEY_AUTO_AI, safe.autoAiEnabled)
            .putFloat(KEY_FONT_SCALE, safe.fontScale)
            .putFloat(KEY_BOTTOM_MARGIN, safe.bottomMarginRatio)
            .putLong(KEY_OFFSET_MS, safe.offsetMs)
            .apply()
    }

    private companion object {
        const val PREFS = "subtitle_prefs"
        const val KEY_ENABLED = "enabled"
        const val KEY_AUTO_AI = "auto_ai"
        const val KEY_FONT_SCALE = "font_scale"
        const val KEY_BOTTOM_MARGIN = "bottom_margin"
        const val KEY_OFFSET_MS = "offset_ms"
    }
}
