package com.roy233.tvcast.app

import android.view.Surface
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.feature.display.DisplayCorners
import com.roy233.tvcast.platform.display.CalibrationPatternPlayer
import com.roy233.tvcast.platform.display.VideoFrameCompositor
import com.roy233.tvcast.platform.display.VideoPoint
import com.roy233.tvcast.platform.display.VideoQuad

/** 解码帧补偿输出的宿主；保存值与校准预览使用同一种 4K 合成路径。 */
class VideoViewportHost(
    private val root: FrameLayout,
    private val playbackView: SurfaceView,
    private val calibrationView: SurfaceView,
    private val onCalibrationFailure: () -> Unit,
) {
    private val playbackCompositor = lazy { VideoFrameCompositor(playbackView) }
    private var calibrationCompositor: VideoFrameCompositor? = null
    private var calibrationPlayer: CalibrationPatternPlayer? = null
    private var saved = DisplayCorners()
    private var completed = false

    fun viewportSize(): Pair<Int, Int> = root.width to root.height

    fun applySaved(corners: DisplayCorners, completed: Boolean) {
        saved = corners.sanitized()
        this.completed = completed
        if (playbackCompositor.isInitialized())
            playbackCompositor.value.setQuad(saved.toVideoQuad())
        LogHub.i(
            "VideoViewport",
            "saved pixel compensation completed=$completed tl=${saved.topLeft} tr=${saved.topRight} br=${saved.bottomRight} bl=${saved.bottomLeft}",
        )
    }

    /** 无补偿时保留硬件直出；有补偿时返回 4K 帧处理器的解码输入 Surface。 */
    fun playbackDecoderSurface(): Surface? {
        if (!completed) return null
        val compositor = playbackCompositor.value
        compositor.setQuad(saved.toVideoQuad())
        return compositor.decoderSurface()
    }

    fun hasCompensation(): Boolean = completed

    /** Media3 自管 Surface 时需要显式保持原视频纵横比；VLC 仍由其 vout 处理。 */
    fun setMedia3ContentAspect(width: Int, height: Int) {
        if (playbackCompositor.isInitialized()) {
            playbackCompositor.value.setContentAspect(
                if (width > 0 && height > 0) width.toFloat() / height else null,
            )
        }
    }

    fun showPlaybackCompensation(visible: Boolean) {
        playbackView.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun showCalibration(corners: DisplayCorners, corner: Int): Boolean {
        val compositor =
            calibrationCompositor
                ?: VideoFrameCompositor(calibrationView, onCalibrationFailure).also {
                    calibrationCompositor = it
                }
        val input =
            compositor.decoderSurface()
                ?: run {
                    hideCalibration(release = true)
                    return false
                }
        compositor.setQuad(corners.toVideoQuad())
        compositor.setMarkerCorner(corner)
        calibrationView.visibility = View.VISIBLE
        if (calibrationPlayer == null) {
            calibrationPlayer = CalibrationPatternPlayer(root.context, input, onCalibrationFailure)
        }
        return runCatching {
            calibrationPlayer?.start()
            true
        }
            .getOrElse { error ->
                LogHub.e("VideoViewport", "calibration playback failed", error, stack = true)
                hideCalibration(release = true)
                false
            }
    }

    fun hideCalibration(release: Boolean) {
        calibrationPlayer?.stop()
        calibrationView.visibility = View.GONE
        if (release) {
            calibrationPlayer?.release()
            calibrationPlayer = null
            calibrationCompositor?.release()
            calibrationCompositor = null
        }
    }

    fun release() {
        hideCalibration(release = true)
        if (playbackCompositor.isInitialized()) playbackCompositor.value.release()
    }

    private fun DisplayCorners.toVideoQuad() =
        VideoQuad(
            topLeft = VideoPoint(topLeft.x, topLeft.y),
            topRight = VideoPoint(topRight.x, topRight.y),
            bottomRight = VideoPoint(bottomRight.x, bottomRight.y),
            bottomLeft = VideoPoint(bottomLeft.x, bottomLeft.y),
        )
}
