package com.roy233.tvcast.feature.danmaku.presentation

import android.content.Context
import android.graphics.Color
import android.view.View
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.feature.danmaku.domain.AiShieldLevel
import com.roy233.tvcast.feature.danmaku.domain.DanmakuAiShield
import com.roy233.tvcast.feature.danmaku.domain.DanmakuItem
import com.roy233.tvcast.feature.danmaku.domain.DanmakuSettings
import com.roy233.tvcast.feature.danmaku.domain.MediaClock
import master.flame.danmaku.controller.DrawHandler
import master.flame.danmaku.danmaku.model.BaseDanmaku
import master.flame.danmaku.danmaku.model.Duration
import master.flame.danmaku.danmaku.model.DanmakuTimer
import master.flame.danmaku.danmaku.model.FBDanmaku
import master.flame.danmaku.danmaku.model.FTDanmaku
import master.flame.danmaku.danmaku.model.IDanmakus
import master.flame.danmaku.danmaku.model.IDisplayer
import master.flame.danmaku.danmaku.model.L2RDanmaku
import master.flame.danmaku.danmaku.model.R2LDanmaku
import master.flame.danmaku.danmaku.model.android.DanmakuContext
import master.flame.danmaku.danmaku.model.android.Danmakus
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser
import master.flame.danmaku.danmaku.util.DanmakuUtils
import master.flame.danmaku.ui.widget.DanmakuView

/**
 * DFM 适配层。
 *
 * 负责创建 [DanmakuView]、注入 [MediaDanmakuTimer]、把 [DanmakuItem] 转成
 * DFM 的 [BaseDanmaku]，并应用显示设置。布局、碰撞、绘制全部交给 DFM。
 */
class DfmDanmakuEngine(
    val view: DanmakuView,
    private val clock: MediaClock,
) {

    /** 供代码创建视图的便捷构造器。 */
    constructor(context: Context, clock: MediaClock) : this(DanmakuView(context), clock)

    private val mediaTimer = MediaDanmakuTimer(clock)
    private val danmakuContext = DanmakuContext.create()
    private val scrollDuration = Duration(BASE_SCROLL_MS)
    private val fixedDuration = Duration(FIXED_MS)

    private var settings = DanmakuSettings()
    private var contentHeightPx = 1080
    private var prepared = false
    private var dfmReady = false
    private var paused = false
    private val pending = ArrayList<DanmakuItem>()

    init {
        view.setBackgroundColor(Color.TRANSPARENT)
        view.isFocusable = false
        view.isClickable = false
        // 使用普通 DrawTask：CacheManagingDrawTask 只有在缓存队列真正构建后
        // 才回调 ready()；我们用空 parser 先 prepare，缓存模式会永远停在
        // dfmReady=false。禁用绘制缓存后 DrawTask.prepare() 同步 ready。
        view.enableDanmakuDrawingCache(false)
        view.setCallback(object : DrawHandler.Callback {
            override fun prepared() {
                LogHub.d(TAG, "dfm callback prepared t=${view.currentTime}")
                view.post {
                    if (!prepared) return@post
                    dfmReady = true
                    // start() 会清空 handler 队列，必须在 PREPARE 完成后再调用；
                    // 随后 resume() 才会真正进入 UPDATE 循环。
                    view.start(clock.nowMs())
                    if (!paused) view.resume() else view.pause()
                    flushPending()
                    LogHub.d(TAG, "dfm started t=${view.currentTime} pending=${pending.size}")
                }
            }

            override fun updateTimer(timer: DanmakuTimer) = Unit

            override fun danmakuShown(danmaku: BaseDanmaku) = Unit

            override fun drawingFinished() = Unit
        })
        danmakuContext.setDanmakuStyle(
            IDisplayer.DANMAKU_STYLE_STROKEN,
            view.resources.displayMetrics.density * STROKE_DP,
        )
        danmakuContext.setDanmakuTransparency(settings.opacity)
        danmakuContext.setScaleTextSize(settings.fontScale)
        danmakuContext.setMaximumVisibleSizeInScreen(settings.maxOnScreen)
        danmakuContext.preventOverlapping(
            mapOf(
                BaseDanmaku.TYPE_SCROLL_RL to true,
                BaseDanmaku.TYPE_SCROLL_LR to true,
                BaseDanmaku.TYPE_FIX_TOP to true,
                BaseDanmaku.TYPE_FIX_BOTTOM to true,
            ),
        )
        scrollDuration.setValue((settings.durationSec * 1000).toLong())
    }

    /**
     * 设置视频内容区高度，用于计算字号。
     *
     * @param heightPx 视频内容高度（不含黑边），单位像素。
     */
    fun setContentHeight(heightPx: Int) {
        if (heightPx > 0) contentHeightPx = heightPx
    }

    /**
     * 视图完成布局后调用；可重复调用，内部只 prepare 一次。
     */
    fun prepare() {
        if (prepared || view.width <= 0 || view.height <= 0) {
            LogHub.i(TAG, "prepare skip prepared=$prepared size=${view.width}x${view.height}")
            return
        }
        val parser = ListDanmakuParser(emptyList()).apply { setTimer(mediaTimer) }
        view.prepare(parser, danmakuContext)
        prepared = true
        LogHub.i(
            TAG,
            "prepare requested size=${view.width}x${view.height} pending=${pending.size} " +
                "t=${clock.nowMs()}",
        )
        if (!settings.enabled) view.visibility = View.GONE
    }

    /**
     * 追加一个分段的弹幕。
     *
     * @param items 原始条目。
     * @param level 智能云屏蔽档位。
     */
    fun addSegment(items: List<DanmakuItem>, level: AiShieldLevel) {
        if (!settings.enabled) return
        val filtered = DanmakuAiShield.filter(items, level)
        LogHub.d(
            TAG,
            "addSegment items=${items.size} filtered=${filtered.size} " +
                "level=$level prepared=$prepared",
        )
        if (!prepared || !dfmReady) {
            pending += filtered
            return
        }
        filtered.forEach(::addToView)
    }

    /**
     * 替换当前分段（设置变化/seek 后重建）。
     */
    fun replaceSegment(items: List<DanmakuItem>, level: AiShieldLevel) {
        pending.clear()
        if (prepared) view.removeAllDanmakus(true)
        addSegment(items, level)
        if (prepared && dfmReady) {
            view.seekTo(clock.nowMs())
            // DFM 的 seek 会结束更新循环；播放中的重建必须显式恢复。
            if (!paused) view.resume()
        }
    }

    /** 应用显示设置。 */
    fun applySettings(newSettings: DanmakuSettings) {
        if (newSettings.opacity != settings.opacity) {
            danmakuContext.setDanmakuTransparency(newSettings.opacity)
        }
        if (newSettings.fontScale != settings.fontScale) {
            danmakuContext.setScaleTextSize(newSettings.fontScale)
        }
        if (newSettings.maxOnScreen != settings.maxOnScreen) {
            danmakuContext.setMaximumVisibleSizeInScreen(newSettings.maxOnScreen)
        }
        if (newSettings.durationSec != settings.durationSec) {
            scrollDuration.setValue((newSettings.durationSec * 1000).toLong())
        }
        if (newSettings.enabled != settings.enabled) {
            view.visibility = if (newSettings.enabled) View.VISIBLE else View.GONE
        }
        settings = newSettings
    }

    /** 暂停。 */
    fun pause() {
        paused = true
        if (prepared) {
            LogHub.d(TAG, "pause t=${view.currentTime}")
            view.pause()
        }
    }

    /** 恢复。 */
    fun resume() {
        paused = false
        if (prepared) {
            LogHub.d(TAG, "resume t=${view.currentTime} prepared=${view.isPrepared}")
            view.resume()
        }
    }

    /** 跳到指定媒体时间。 */
    fun seekTo(mediaMs: Long) {
        if (prepared) {
            LogHub.d(TAG, "seekTo $mediaMs from t=${view.currentTime}")
            view.seekTo(mediaMs)
        }
    }

    /** 清空屏幕上的弹幕与等待队列。 */
    fun clear() {
        pending.clear()
        if (prepared) view.removeAllDanmakus(true)
    }

    /** 释放 DFM 资源。 */
    fun release() {
        if (prepared) {
            view.release()
            prepared = false
        }
        pending.clear()
    }

    private fun flushPending() {
        if (pending.isEmpty()) return
        val copy = pending.toList()
        pending.clear()
        copy.forEach(::addToView)
    }

    private fun addToView(item: DanmakuItem) {
        view.addDanmaku(toDfm(item))
    }

    private fun toDfm(item: DanmakuItem): BaseDanmaku {
        val danmaku = when (item.mode) {
            4 -> FBDanmaku(fixedDuration)
            5 -> FTDanmaku(fixedDuration)
            6 -> L2RDanmaku(scrollDuration)
            else -> R2LDanmaku(scrollDuration)
        }
        danmaku.text = item.content
        danmaku.textSize = fontPx(item.fontSize)
        danmaku.textColor = 0xFF000000.toInt() or (item.color and 0xFFFFFF)
        danmaku.textShadowColor = if (isLight(item.color)) Color.BLACK else Color.WHITE
        danmaku.setTime(item.progressMs.toLong())
        DanmakuUtils.fillText(danmaku, item.content)
        return danmaku
    }

    private fun fontPx(fontSize: Int): Float {
        return (fontSize * contentHeightPx / REFERENCE_HEIGHT * BASE_FONT_FACTOR)
            .coerceAtLeast(MIN_FONT_PX)
    }

    private fun isLight(color: Int): Boolean {
        val red = (color shr 16) and 0xFF
        val green = (color shr 8) and 0xFF
        val blue = color and 0xFF
        return red * 0.299f + green * 0.587f + blue * 0.114f > 186f
    }

    private class ListDanmakuParser(
        private val items: List<BaseDanmaku>,
    ) : BaseDanmakuParser() {

        override fun parse(): IDanmakus {
            val result = Danmakus(IDanmakus.ST_BY_TIME)
            items.forEach { result.addItem(it) }
            return result
        }
    }

    private companion object {
        const val TAG = "DfmDanmaku"
        const val BASE_SCROLL_MS = 8_000L
        const val FIXED_MS = 4_000L
        const val REFERENCE_HEIGHT = 438f
        const val BASE_FONT_FACTOR = 0.8f
        const val MIN_FONT_PX = 12f
        const val STROKE_DP = 1.2f
    }
}
