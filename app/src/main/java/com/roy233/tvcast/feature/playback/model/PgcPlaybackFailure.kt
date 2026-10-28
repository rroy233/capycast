package com.roy233.tvcast.feature.playback.model

import java.io.IOException

/** PGC 解析对应用层公开的结构化失败信息。 */
class PgcPlaybackFailure(
    val errorCode: String,
    val userMessage: String,
    val apiCode: Int? = null,
    val httpStatus: Int? = null,
    cause: Throwable? = null,
) : IOException(userMessage, cause)
