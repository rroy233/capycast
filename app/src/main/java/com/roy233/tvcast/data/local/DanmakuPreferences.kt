package com.roy233.tvcast.data.local

import android.content.Context
import com.roy233.tvcast.core.danmaku.AiShieldLevel
import com.roy233.tvcast.core.danmaku.DanmakuSettings

/**
 * 弹幕显示设置持久化。
 *
 * 只有简单的显示偏好和智能云屏蔽档位；不保存任何过滤规则内容。
 *
 * @param context 用于访问应用偏好的上下文。
 */
class DanmakuPreferences(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 读取已保存的设置，非法值回退到默认值。 */
    fun load(): DanmakuSettings {
        val level = runCatching {
            AiShieldLevel.valueOf(prefs.getString(KEY_AI_SHIELD, AiShieldLevel.OFF.name)!!)
        }.getOrDefault(AiShieldLevel.OFF)
        return DanmakuSettings(
            enabled = prefs.getBoolean(KEY_ENABLED, true),
            aiShield = level,
            opacity = prefs.getFloat(KEY_OPACITY, 1f).coerceIn(0.2f, 1f),
            fontScale = prefs.getFloat(KEY_FONT_SCALE, 1f).coerceIn(0.5f, 2f),
            durationSec = prefs.getFloat(KEY_DURATION, 8f).coerceIn(5f, 12f),
            areaRatio = prefs.getFloat(KEY_AREA, 0.25f).coerceIn(0.25f, 1f),
            maxOnScreen = prefs.getInt(KEY_MAX_ON_SCREEN, 60).coerceIn(0, 100),
        )
    }

    /** 保存设置。 */
    fun save(settings: DanmakuSettings) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putString(KEY_AI_SHIELD, settings.aiShield.name)
            .putFloat(KEY_OPACITY, settings.opacity)
            .putFloat(KEY_FONT_SCALE, settings.fontScale)
            .putFloat(KEY_DURATION, settings.durationSec)
            .putFloat(KEY_AREA, settings.areaRatio)
            .putInt(KEY_MAX_ON_SCREEN, settings.maxOnScreen)
            .apply()
    }

    private companion object {
        const val PREFS = "danmaku_prefs"
        const val KEY_ENABLED = "enabled"
        const val KEY_AI_SHIELD = "ai_shield"
        const val KEY_OPACITY = "opacity"
        const val KEY_FONT_SCALE = "font_scale"
        const val KEY_DURATION = "duration_sec"
        const val KEY_AREA = "area_ratio"
        const val KEY_MAX_ON_SCREEN = "max_on_screen"
    }
}
