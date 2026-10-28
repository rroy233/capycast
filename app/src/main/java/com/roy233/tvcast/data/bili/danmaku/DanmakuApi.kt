package com.roy233.tvcast.data.bili.danmaku

import com.roy233.tvcast.data.bili.BiliApi
import com.roy233.tvcast.data.bili.BiliHttpClient
import com.roy233.tvcast.data.auth.BiliSessionReader
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * B 站弹幕接口。
 *
 * 只负责发请求和解码，不做缓存/调度；失败时由调用方决定是否重试。
 */
class DanmakuApi(
    private val sessionReader: BiliSessionReader,
    client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
) {
    private val http = BiliHttpClient(client, BiliApi.UA)
    private fun getBytes(url: String, purpose: String): ByteArray =
        http.getBytes(url, purpose, sessionReader.cookie())

    /**
     * 拉取弹幕元数据。
     *
     * @param cid 视频 cid。
     */
    fun fetchView(cid: Long): DmViewInfo {
        val url = "$BASE/x/v2/dm/web/view?type=1&oid=$cid"
        return DanmakuProto.decodeView(getBytes(url, "danmaku-view"))
    }

    /**
     * 拉取一个 6 分钟分段。
     *
     * 优先 web 端点；失败时降级到 App 端点。
     *
     * @param aid 稿件 avid。
     * @param cid 视频 cid。
     * @param segmentIndex 1 开始的分段号。
     */
    fun fetchSegment(aid: Long, cid: Long, segmentIndex: Int): DmSegment {
        val pid = if (aid > 0) "&pid=$aid" else ""
        val web = "$BASE/x/v2/dm/web/seg.so?type=1&oid=$cid$pid&segment_index=$segmentIndex"
        return try {
            DanmakuProto.decodeSegment(getBytes(web, "danmaku-seg"))
        } catch (webFailure: Exception) {
            val app = "$BASE/x/v2/dm/list/seg.so?type=1&oid=$cid$pid&segment_index=$segmentIndex"
            try {
                DanmakuProto.decodeSegment(getBytes(app, "danmaku-seg-app"))
            } catch (appFailure: Exception) {
                appFailure.addSuppressed(webFailure)
                throw appFailure
            }
        }
    }

    private companion object {
        const val BASE = "https://api.bilibili.com"
    }
}
