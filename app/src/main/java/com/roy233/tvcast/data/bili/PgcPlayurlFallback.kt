package com.roy233.tvcast.data.bili

import com.google.gson.JsonObject
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction
import com.roy233.tvcast.feature.playback.model.PgcPlaybackFailure as PgcPlaybackException

/** PGC playurl v2 与 legacy 的既有回退次序和错误分类。 */
internal class PgcPlayurlFallback(
    private val request: (String, Map<String, String>, String, String) -> PgcPlaybackResolver.PlayurlEnvelope,
) {
    fun resolve(params: Map<String, String>, cookie: String, epId: Long): JsonObject {
        val v2 = request(V2_PLAYURL_PATH, params, cookie, "pgc-playurl-v2")
        return when {
            v2.code == 0 && v2.play != null -> v2.play
            v2.code == 0 || v2.code in V2_LEGACY_FALLBACK_CODES -> {
                LogHub.w(
                    TAG,
                    "PGC v2 response requires legacy fallback epId=$epId " +
                        "code=${v2.code} hasPayload=${v2.play != null}",
                )
                val legacy = request(LEGACY_PLAYURL_PATH, params, cookie, "pgc-playurl-legacy")
                if (legacy.code != 0) throw playurlFailure("legacy", epId, legacy.code, legacy.message)
                legacy.play ?: throw PgcPlaybackException(
                    "PGC_MEDIA_UNAVAILABLE",
                    "B 站未返回可解析的番剧播放信息",
                    apiCode = legacy.code,
                )
            }
            else -> throw playurlFailure("v2", epId, v2.code, v2.message)
        }
    }

    private fun playurlFailure(endpoint: String, epId: Long, code: Int, message: String) =
        PgcPlaybackException(
            errorCode = if (code == -101 || code == -111) "PGC_LOGIN_REQUIRED" else "PGC_PLAYURL_FAILED",
            userMessage = when (code) {
                -101, -111 -> "请重新登录 B 站账号后重试"
                -10403 -> "该番剧在当前地区不可播放"
                else -> "B 站拒绝了番剧播放请求（code=$code）"
            },
            apiCode = code,
        ).also {
            LogHub.e(
                TAG,
                "PGC playurl rejected endpoint=$endpoint epId=$epId code=$code " +
                    "message=${LogRedaction.text(message).take(120)}",
            )
        }

    private companion object {
        const val TAG = "PgcResolver"
        const val V2_PLAYURL_PATH = "/pgc/player/web/v2/playurl"
        const val LEGACY_PLAYURL_PATH = "/pgc/player/web/playurl"
        val V2_LEGACY_FALLBACK_CODES = setOf(-400, -404)
    }
}
