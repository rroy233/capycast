package com.roy233.tvcast.platform.display

import kotlin.math.abs

/** 平台渲染器的归一化四边形输入；由应用宿主从校准状态映射而来。 */
data class VideoPoint(val x: Float, val y: Float)

data class PerspectiveWeights(val topLeft: Float, val topRight: Float, val bottomRight: Float, val bottomLeft: Float)

data class VideoQuad(
    val topLeft: VideoPoint = VideoPoint(0f, 0f),
    val topRight: VideoPoint = VideoPoint(1f, 0f),
    val bottomRight: VideoPoint = VideoPoint(1f, 1f),
    val bottomLeft: VideoPoint = VideoPoint(0f, 1f),
) {
    /** 在校准四边形内等比放置非 16:9 内容，不改变原视频纵横比。 */
    fun fitInside(contentAspect: Float, outputAspect: Float): VideoQuad {
        if (!contentAspect.isFinite() || contentAspect <= 0f || !outputAspect.isFinite() || outputAspect <= 0f) {
            return this
        }
        if (contentAspect < outputAspect) {
            val padding = (1f - contentAspect / outputAspect) / 2f
            return VideoQuad(
                topLeft = lerp(topLeft, topRight, padding),
                topRight = lerp(topRight, topLeft, padding),
                bottomRight = lerp(bottomRight, bottomLeft, padding),
                bottomLeft = lerp(bottomLeft, bottomRight, padding),
            )
        }
        val padding = (1f - outputAspect / contentAspect) / 2f
        return VideoQuad(
            topLeft = lerp(topLeft, bottomLeft, padding),
            topRight = lerp(topRight, bottomRight, padding),
            bottomRight = lerp(bottomRight, topRight, padding),
            bottomLeft = lerp(bottomLeft, topLeft, padding),
        )
    }

    fun point(corner: Int): VideoPoint = when (corner) {
        1 -> topLeft
        2 -> topRight
        3 -> bottomRight
        else -> bottomLeft
    }

    /** 投影插值权重，使任意四角仍按连续透视映射取样。 */
    fun perspectiveWeights(): PerspectiveWeights {
        val sx = topLeft.x - topRight.x + bottomRight.x - bottomLeft.x
        val sy = topLeft.y - topRight.y + bottomRight.y - bottomLeft.y
        val dx1 = topRight.x - bottomRight.x
        val dy1 = topRight.y - bottomRight.y
        val dx2 = bottomLeft.x - bottomRight.x
        val dy2 = bottomLeft.y - bottomRight.y
        val determinant = dx1 * dy2 - dx2 * dy1
        if (abs(determinant) < 0.000001f) return PerspectiveWeights(1f, 1f, 1f, 1f)
        val g = (sx * dy2 - sy * dx2) / determinant
        val h = (dx1 * sy - dy1 * sx) / determinant
        return PerspectiveWeights(1f, 1f + g, 1f + g + h, 1f + h)
    }

    private fun lerp(from: VideoPoint, to: VideoPoint, fraction: Float) = VideoPoint(
        from.x + (to.x - from.x) * fraction,
        from.y + (to.y - from.y) * fraction,
    )
}
