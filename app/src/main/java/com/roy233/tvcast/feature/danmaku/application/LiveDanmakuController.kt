package com.roy233.tvcast.feature.danmaku.application

import android.content.Context
import com.roy233.tvcast.feature.danmaku.domain.DanmakuItem
import com.roy233.tvcast.feature.danmaku.domain.LiveDanmakuClient
import com.roy233.tvcast.feature.danmaku.domain.LiveDanmakuClientFactory
import com.roy233.tvcast.feature.danmaku.domain.AiShieldLevel
import com.roy233.tvcast.feature.danmaku.domain.DanmakuSettings
import com.roy233.tvcast.feature.danmaku.domain.MediaClock
import com.roy233.tvcast.feature.danmaku.presentation.DfmDanmakuEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 直播弹幕会话控制器。
 *
 * 复用点播的 [DfmDanmakuEngine] 渲染层与 [MediaClock]，但数据源替换为
 * [LiveDanmakuClient] 的实时长连接：直播没有绝对时间轴，时钟从 0 单调递增，
 * 每条弹幕以当前时钟作为出现时刻。暂停时冻结画面并丢弃暂停期间的增量，
 * 避免恢复瞬间补刷。
 *
 * 所有公开方法都应在主线程调用。
 */
class LiveDanmakuController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val engine: DfmDanmakuEngine,
    private val clock: MediaClock,
    private val clientFactory: LiveDanmakuClientFactory,
) {

    private var client: LiveDanmakuClient? = null
    private var settings = DanmakuSettings()
    private var closed = true
    private var paused = false
    private var activeRoomId = -1L
    private val pending = ArrayList<DanmakuItem>()
    private var flushJob: Job? = null

    /** 设置视频内容区高度，用于字号换算。 */
    fun setContentHeight(heightPx: Int) {
        engine.setContentHeight(heightPx)
    }

    /** 视图完成布局后调用，初始化 DFM。 */
    fun prepare() {
        engine.prepare()
    }

    /**
     * 打开一个直播间的弹幕会话。
     *
     * @param roomId 直播间标识。
     * @param cookie 登录 Cookie，供 getDanmuInfo 换取进房 token。
     * @param uid 登录用户 mid；游客为 0。
     */
    fun open(roomId: Long, cookie: String, uid: Long) {
        // 同房间内切换线路时复用现有连接，避免不必要的重连与 token 重换。
        if (!closed && activeRoomId == roomId) return
        activeRoomId = roomId
        closed = false
        paused = false
        pending.clear()
        flushJob?.cancel()
        flushJob = null
        engine.clear()
        clock.reset()
        clock.rebase(0)
        client?.close()
        client = clientFactory.create(scope, context, roomId, cookie, uid, ::onItems, {})
        client?.start()
    }

    /** 关闭会话并清空屏幕。 */
    fun close() {
        closed = true
        activeRoomId = -1L
        paused = false
        client?.close()
        client = null
        flushJob?.cancel()
        flushJob = null
        pending.clear()
        engine.clear()
        clock.reset()
    }

    /** 播放器恢复播放。 */
    fun onPlaying() {
        if (closed) return
        paused = false
        clock.rebase(clock.nowMs())
        engine.resume()
    }

    /** 播放器暂停；暂停期间的增量直接丢弃。 */
    fun onPaused() {
        if (closed) return
        paused = true
        pending.clear()
        clock.pause(clock.nowMs())
        engine.pause()
    }

    /** 应用显示设置；直播弹幕无智能云屏蔽，强制按关闭过滤。 */
    fun applySettings(newSettings: DanmakuSettings) {
        settings = newSettings
        engine.applySettings(newSettings)
    }

    /** 释放会话；Activity 销毁时调用。 */
    fun release() {
        close()
    }

    private fun onItems(items: List<DanmakuItem>) {
        if (closed || paused || !settings.enabled) return
        // okhttp 回调线程切到主线程，避免与 flush 竞争 pending。
        scope.launch {
            if (closed || paused || !settings.enabled) return@launch
            pending += items
            if (flushJob?.isActive != true) {
                flushJob = scope.launch {
                    delay(FLUSH_INTERVAL_MS)
                    flush()
                }
            }
        }
    }

    private fun flush() {
        if (closed || paused || pending.isEmpty()) return
        val now = clock.nowMs().toInt()
        val batch = pending.map { it.copy(progressMs = now) }
        pending.clear()
        engine.addSegment(batch, AiShieldLevel.OFF)
    }

    private companion object {
        /** 攒批间隔，避免高频弹幕逐条触发 DFM 添加。 */
        const val FLUSH_INTERVAL_MS = 100L
    }
}
