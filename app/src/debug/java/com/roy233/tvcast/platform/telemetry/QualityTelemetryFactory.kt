package com.roy233.tvcast.platform.telemetry

import android.content.Context
import com.roy233.tvcast.core.diagnostics.NoOpQualityTelemetry
import com.roy233.tvcast.core.diagnostics.QualityTelemetry

/** 创建 Debug 变体使用的空遥测实现。 */
@Suppress("UNUSED_PARAMETER")
internal fun createQualityTelemetry(context: Context): QualityTelemetry = NoOpQualityTelemetry
