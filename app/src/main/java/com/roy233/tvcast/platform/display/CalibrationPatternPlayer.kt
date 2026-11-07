package com.roy233.tvcast.platform.display

import android.content.Context
import android.net.Uri
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction

/** 循环硬解项目内的 4K H.264 静态视频；画面与角标经同一像素输出链显示。 */
class CalibrationPatternPlayer(
    context: Context,
    surface: Surface,
    private val onFailure: () -> Unit,
) {
    private val player =
        ExoPlayer.Builder(context.applicationContext).build().apply {
            setVideoSurface(surface)
            repeatMode = Player.REPEAT_MODE_ONE
            addListener(
                object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (videoSize.width == 0 && videoSize.height == 0) {
                            LogHub.d(TAG, "calibration decoder cleared")
                            return
                        }
                        LogHub.i(TAG, "calibration decoded=${videoSize.width}x${videoSize.height}")
                        if (
                            started &&
                                isUnexpectedCalibrationVideoSize(videoSize.width, videoSize.height)
                        ) {
                            LogHub.e(TAG, "calibration requires 3840x2160 decode")
                            onFailure()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        LogHub.e(
                            TAG,
                            "calibration decode failed: ${LogRedaction.reason(error)}",
                            error,
                            stack = true,
                        )
                        if (started) onFailure()
                    }
                },
            )
        }
    private var started = false

    fun start() {
        if (started) return
        started = true
        try {
            player.setMediaItem(MediaItem.fromUri(Uri.parse("asset:///calibration_4k.mp4")))
            player.prepare()
            player.play()
        } catch (error: Throwable) {
            started = false
            throw error
        }
    }

    fun stop() {
        if (!started) return
        started = false
        player.stop()
    }

    fun release() {
        started = false
        player.release()
    }

    private companion object {
        const val TAG = "CalibrationVideo"
    }
}

/** Media3 在 stop/release 时回报 0×0；这不是解码失败。 */
internal fun isUnexpectedCalibrationVideoSize(width: Int, height: Int): Boolean =
    (width != 0 || height != 0) &&
        (width != VideoFrameCompositor.OUTPUT_WIDTH || height != VideoFrameCompositor.OUTPUT_HEIGHT)
