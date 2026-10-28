package com.roy233.tvcast.data.bili.pgc

import android.util.Base64
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction
import com.roy233.tvcast.data.bili.BiliApi
import com.roy233.tvcast.feature.playback.model.PgcPlaybackFailure as PgcPlaybackException
import okhttp3.OkHttpClient
import okhttp3.Request

/** 只负责读取 DASH 初始化区间并提取 Widevine PSSH。 */
class PgcPsshReader(private val client: OkHttpClient) {
    fun attach(info: BiliApi.PlayInfo): BiliApi.PlayInfo {
        val tracks = listOfNotNull(info.video, info.audio)
        val enriched = tracks.map { track ->
            if (track.initializationRange.isBlank()) return@map track
            val pssh = fetchPssh(track)
            track.copy(psshBase64 = pssh)
        }
        val video = enriched.firstOrNull() ?: info.video
        val audio = enriched.getOrNull(1)
        if (enriched.none { it.psshBase64.isNotBlank() }) {
            throw PgcPlaybackException(
                "PGC_DRM_METADATA_INVALID",
                "受保护媒体缺少 Widevine 初始化数据",
            )
        }
        return info.copy(video = video, audio = audio)
    }

    private fun fetchPssh(track: BiliApi.Track): String {
        val range = parseByteRange(track.initializationRange)
            ?: throw PgcPlaybackException("PGC_DRM_METADATA_INVALID", "Widevine 初始化范围无效")
        if (range.second - range.first + 1L > MAX_INIT_RANGE_BYTES) {
            throw PgcPlaybackException("PGC_DRM_METADATA_INVALID", "Widevine 初始化数据范围过大")
        }
        val request = Request.Builder()
            .url(track.url)
            .header("User-Agent", BiliApi.UA)
            .header("Referer", WEB_REFERER)
            .header("Origin", WEB_ORIGIN)
            .header("Range", "bytes=${range.first}-${range.second}")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.code != 206 && !response.isSuccessful) {
                    LogHub.e(
                        TAG,
                        "PGC DRM initialization range failed status=${response.code} " +
                            "host=${LogRedaction.host(track.url)}",
                    )
                    throw PgcPlaybackException("PGC_DRM_METADATA_INVALID", "读取 Widevine 初始化数据失败")
                }
                val bytes = response.body?.source()?.readByteArray(MAX_INIT_RANGE_BYTES + 1L)
                    ?: throw PgcPlaybackException("PGC_DRM_METADATA_INVALID", "Widevine 初始化数据为空")
                if (bytes.size.toLong() > MAX_INIT_RANGE_BYTES) {
                    throw PgcPlaybackException("PGC_DRM_METADATA_INVALID", "Widevine 初始化数据超出限制")
                }
                val pssh = PsshBoxParser.findWidevinePssh(bytes)
                if (pssh == null) {
                    LogHub.e(TAG, "PGC DRM init data has no Widevine PSSH host=${LogRedaction.host(track.url)}")
                    return ""
                }
                return Base64.encodeToString(pssh, Base64.NO_WRAP)
            }
        } catch (failure: PgcPlaybackException) {
            throw failure
        } catch (failure: Exception) {
            LogHub.e(
                TAG,
                "PGC DRM initialization read error=${LogRedaction.reason(failure)} " +
                    "host=${LogRedaction.host(track.url)}",
            )
            throw PgcPlaybackException("PGC_DRM_METADATA_INVALID", "读取 Widevine 初始化数据失败", cause = failure)
        }
    }

    private fun parseByteRange(raw: String): Pair<Long, Long>? {
        val parts = raw.trim().split('-', limit = 2)
        if (parts.size != 2) return null
        val start = parts[0].toLongOrNull() ?: return null
        val end = parts[1].toLongOrNull() ?: return null
        return (start to end).takeIf { start >= 0L && end >= start }
    }

    private companion object {
        const val TAG = "PgcResolver"
        const val WEB_REFERER = "https://www.bilibili.com/"
        const val WEB_ORIGIN = "https://www.bilibili.com"
        const val MAX_INIT_RANGE_BYTES = 1024L * 1024L
    }
}
