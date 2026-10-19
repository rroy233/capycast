package com.roy233.tvcast.platform.player

import android.content.Context
import android.net.Uri
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import com.roy233.tvcast.data.bili.BiliApi

/**
 * 管理 VLC 播放器生命周期和媒体初始化。
 *
 * @param context 用于创建 VLC 运行时的上下文。
 * @param videoLayout 播放视频所使用的布局。
 * @param onEvent VLC 播放器事件回调。
 */
class VlcPlayerEngine(
    context: Context,
    videoLayout: VLCVideoLayout,
    onEvent: (MediaPlayer.Event) -> Unit,
) {

    /** VLC 原生运行时实例。 */
    private val libVlc = LibVLC(context, arrayListOf(
        "--avcodec-hw=mediacodec",
        "--no-drop-late-frames",
        "--no-skip-frames",
        "--network-caching=2000",
        "--audio-time-stretch",
        "--android-display-chroma=RV32",
        "--verbose=1",
    ))

    /** 对外提供的 VLC 播放器实例。 */
    val player = MediaPlayer(libVlc)

    /** 将播放器绑定到视频布局并注册事件监听器。 */
    init {
        player.attachViews(videoLayout, null, false, false)
        player.setEventListener(onEvent)
    }

    /**
     * 打开播放信息对应的媒体并开始播放。
     *
     * @param info 已解析的播放信息。
     * @param seekToMs 开始播放前需要跳转的位置，单位为毫秒。
     */
    fun open(info: BiliApi.PlayInfo, seekToMs: Long) {
        val media = Media(libVlc, Uri.parse(info.progressiveUrl ?: info.video.url))
        runCatching { media.setHWDecoderEnabled(true, false) }
        if (info.progressiveUrl == null) {
            info.audio?.let { media.addOption(":input-slave=${it.url}") }
        }
        if (seekToMs > 0) {
            media.addOption(":start-time=${seekToMs / 1000.0}")
        }
        media.addOption(":http-referrer=https://www.bilibili.com/")
        media.addOption(":http-user-agent=${BiliApi.UA}")
        media.addOption(":network-caching=2000")
        player.media = media
        media.release()
        player.play()
    }

    /** 停止播放并释放播放器及原生运行时资源。 */
    fun release() {
        runCatching { player.stop() }
        runCatching { player.detachViews() }
        runCatching { player.release() }
        runCatching { libVlc.release() }
    }
}
