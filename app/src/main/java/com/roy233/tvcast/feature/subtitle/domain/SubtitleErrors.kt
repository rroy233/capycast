package com.roy233.tvcast.feature.subtitle.domain

/**
 * 字幕数据不可用。
 *
 * @property code 便于诊断分类的错误码。
 */
class SubtitleUnavailableException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
