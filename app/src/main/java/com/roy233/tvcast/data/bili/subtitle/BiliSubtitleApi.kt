package com.roy233.tvcast.data.bili.subtitle

import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction
import com.roy233.tvcast.data.auth.BiliSessionReader
import com.roy233.tvcast.data.bili.BiliApi
import com.roy233.tvcast.data.bili.BiliHttpClient
import com.roy233.tvcast.data.bili.WbiSigner
import com.roy233.tvcast.feature.subtitle.domain.SubtitleContent
import com.roy233.tvcast.feature.subtitle.domain.SubtitleCue
import com.roy233.tvcast.feature.subtitle.domain.SubtitleTrack
import com.roy233.tvcast.feature.subtitle.domain.SubtitleUnavailableException
import com.roy233.tvcast.feature.subtitle.domain.SubtitleUrlPolicy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * B 站字幕接口。
 *
 * 轨道列表来自 WBI 签名的播放器信息接口，正文是外挂 JSON；两者都在 IO 线程发起且可取消。
 */
class BiliSubtitleApi(
    private val sessionReader: BiliSessionReader,
    private val wbiSigner: WbiSigner,
    client: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build(),
) : SubtitleRemote {

    private val http = BiliHttpClient(client, BiliApi.UA)

    /** 拉取视频的全部可用字幕轨道。 */
    override suspend fun fetchTracks(content: SubtitleContent): List<SubtitleTrack> =
        withContext(Dispatchers.IO) {
            if (content.bvid.isBlank() && content.epId <= 0L) {
                throw SubtitleUnavailableException(
                    code = "SUBTITLE_CONTENT_UNRESOLVED",
                    message = "缺少字幕定位所需的稿件标识",
                )
            }
            val cookie = sessionReader.cookie()
            val params = playerInfoParams(content)
            val signed =
                try {
                    wbiSigner.sign(params)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    LogHub.e(
                        TAG,
                        "subtitle tracks signing failed cid=${content.cid} ep=${content.epId} " +
                            "error=${LogRedaction.reason(failure)}",
                    )
                    throw SubtitleUnavailableException(
                        code = "SUBTITLE_SIGN_FAILED",
                        message = "字幕轨道签名失败",
                        cause = failure,
                    )
                }
            val urlBuilder = PLAYER_INFO_PATH.toHttpUrl().newBuilder()
            signed.forEach { (key, value) -> urlBuilder.addQueryParameter(key, value) }
            val url = urlBuilder.build().toString()
            val bytes =
                try {
                    http.getBytesCancellable(url, "subtitle-tracks", cookie)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    LogHub.e(
                        TAG,
                        "subtitle tracks failed cid=${content.cid} ep=${content.epId} " +
                            "error=${LogRedaction.reason(failure)}",
                    )
                    throw SubtitleUnavailableException(
                        code = "SUBTITLE_TRACKS_FAILED",
                        message = "字幕轨道请求失败",
                        cause = failure,
                    )
                }
            SubtitleJson.tracks(String(bytes, Charsets.UTF_8), content)
        }

    /** 拉取指定轨道的完整正文。 */
    override suspend fun fetchCues(track: SubtitleTrack): List<SubtitleCue> =
        withContext(Dispatchers.IO) {
            if (!SubtitleUrlPolicy.isTrusted(track.url)) {
                throw SubtitleUnavailableException(
                    code = "SUBTITLE_URL_REJECTED",
                    message = "字幕地址不在可信域名内",
                )
            }
            val bytes =
                try {
                    http.getBytesCancellable(track.url, "subtitle-body", sessionReader.cookie())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    LogHub.e(
                        TAG,
                        "subtitle body failed lan=${track.lan} id=${track.id} " +
                            "error=${LogRedaction.reason(failure)}",
                    )
                    throw SubtitleUnavailableException(
                        code = "SUBTITLE_BODY_FAILED",
                        message = "字幕正文请求失败",
                        cause = failure,
                    )
                }
            SubtitleJson.cues(String(bytes, Charsets.UTF_8))
        }

    /** 构建播放器信息接口参数；番剧额外携带 ep_id 定位分集字幕。 */
    private fun playerInfoParams(content: SubtitleContent): Map<String, String> = buildMap {
        if (content.bvid.isNotBlank()) put("bvid", content.bvid)
        if (content.epId > 0L) put("ep_id", content.epId.toString())
        put("cid", content.cid.toString())
    }

    private companion object {
        /** 日志标签。 */
        const val TAG = "BiliSubtitle"

        /** 播放器信息接口，返回字幕轨道列表。 */
        const val PLAYER_INFO_PATH = "https://api.bilibili.com/x/player/wbi/v2"
    }
}
