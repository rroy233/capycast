package com.roy233.tvcast.ui.designsystem

import android.graphics.Bitmap
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.roundToInt

/**
 * Android 12 以下的磨砂来源。
 *
 * 背景模糊（`RenderEffect`）只在 Android 12 起可用，旧机型上第三方玻璃库的 `blur()`
 * 会直接返回，玻璃因此只剩一层平色。这里的做法与桌面系统一致：把背景截成位图，用多级 降采样再逐级放大的方式做近似高斯模糊，玻璃表面按自身位置采样这张模糊图。
 *
 * @property image 已模糊的背景快照。
 * @property windowWidth 采样时窗口宽度（像素），用于把表面坐标映射回快照坐标。
 * @property windowHeight 采样时窗口高度（像素）。
 */
class FrostedBackdrop
internal constructor(
    val image: ImageBitmap,
    val windowWidth: Int,
    val windowHeight: Int,
)

/** 当前可用的磨砂背景；为 null 时玻璃退化为纯色霜面。 */
val LocalFrostedBackdrop = staticCompositionLocalOf<FrostedBackdrop?> { null }

/** 降采样倍数：越大越糊，也越省内存。电视上 8 倍足够遮住桌面细节。 */
private const val BLUR_DOWNSAMPLE_FACTOR = 8

/**
 * 用多级降采样 + 逐级放大近似高斯模糊。
 *
 * 逐级放大的每次双线性插值都相当于一次额外的低通滤波，效果比一次性放大柔和， 代价只是一串小位图拷贝，可以放在后台线程完成。
 */
internal fun ImageBitmap.progressiveBlur(): ImageBitmap {
    val source = asAndroidBitmap()
    if (source.width < 2 || source.height < 2) return this

    val minWidth = (source.width / BLUR_DOWNSAMPLE_FACTOR).coerceAtLeast(1)
    var small = source
    while (small.width / 2 >= minWidth && small.height / 2 >= 1) {
        small = small.scaledTo(small.width / 2)
    }

    var blurred = small
    while (blurred.width < source.width) {
        blurred = blurred.scaledTo((blurred.width * 2).coerceAtMost(source.width))
    }
    return blurred.asImageBitmap()
}

/** 按比例缩放位图并启用双线性过滤。 */
private fun Bitmap.scaledTo(width: Int): Bitmap {
    val height = (height.toFloat() * width / this.width).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, width, height, true)
}
