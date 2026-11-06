package com.roy233.tvcast.data.local

import android.content.Context
import com.roy233.tvcast.feature.display.CalibrationPoint
import com.roy233.tvcast.feature.display.DisplayCalibrationStore
import com.roy233.tvcast.feature.display.DisplayCorners
import com.roy233.tvcast.feature.display.SavedCalibration

/** 旧版 View 缩放参数无效，已保存过该参数的用户需要重新校准视频像素。 */
class DisplayCalibrationPreferences(context: Context) : DisplayCalibrationStore {
    private val prefs = context.getSharedPreferences("display_calibration", Context.MODE_PRIVATE)

    override fun load(): SavedCalibration {
        val current = prefs.getInt("pixel_compensation_version", 0) == PIXEL_COMPENSATION_VERSION
        val hadOldCalibration = listOf("left", "top", "right", "bottom")
            .any { prefs.getFloat(it, 0f) != 0f }
        val corners = if (current) DisplayCorners(
            topLeft = CalibrationPoint(prefs.getFloat("tl_x", 0f), prefs.getFloat("tl_y", 0f)),
            topRight = CalibrationPoint(prefs.getFloat("tr_x", 1f), prefs.getFloat("tr_y", 0f)),
            bottomRight = CalibrationPoint(prefs.getFloat("br_x", 1f), prefs.getFloat("br_y", 1f)),
            bottomLeft = CalibrationPoint(prefs.getFloat("bl_x", 0f), prefs.getFloat("bl_y", 1f)),
        ).sanitized() else DisplayCorners()
        return SavedCalibration(
            prompted = prefs.getBoolean("prompted", false) && (current || !hadOldCalibration),
            corners = corners,
            completed = current && prefs.getBoolean("completed", false),
        )
    }

    override fun savePrompted() {
        prefs.edit()
            .putBoolean("prompted", true)
            .putInt("pixel_compensation_version", PIXEL_COMPENSATION_VERSION)
            .apply()
    }

    override fun save(corners: DisplayCorners) {
        val safe = corners.sanitized()
        prefs.edit()
            .putBoolean("prompted", true)
            .putBoolean("completed", true)
            .putInt("pixel_compensation_version", PIXEL_COMPENSATION_VERSION)
            .putFloat("tl_x", safe.topLeft.x).putFloat("tl_y", safe.topLeft.y)
            .putFloat("tr_x", safe.topRight.x).putFloat("tr_y", safe.topRight.y)
            .putFloat("br_x", safe.bottomRight.x).putFloat("br_y", safe.bottomRight.y)
            .putFloat("bl_x", safe.bottomLeft.x).putFloat("bl_y", safe.bottomLeft.y)
            .apply()
    }

    private companion object { const val PIXEL_COMPENSATION_VERSION = 3 }
}
