package com.roy233.tvcast.platform.telemetry

import android.content.Context
import com.roy233.tvcast.core.diagnostics.QualityTelemetry

/** 创建 Release 变体使用的 Firebase 遥测实现。 */
internal fun createQualityTelemetry(context: Context): QualityTelemetry =
    FirebaseQualityTelemetry(context)
