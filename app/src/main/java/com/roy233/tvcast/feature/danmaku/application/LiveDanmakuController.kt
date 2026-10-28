package com.roy233.tvcast.feature.danmaku.application

import android.content.Context
import com.roy233.tvcast.core.diagnostics.LogHub
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
import kotlinx.coroutines.isActive
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
    private var diagnosticJob: Job? = null
    private var sessionSerial = 0L

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
        if (!closed && activeRoomId == roomId) {
            LogHub.d(TAG, "reuse session=$sessionSerial room=$roomId " + engine.diagnosticState())
            return
        }
        LogHub.i(
            TAG,
            "open room=$roomId previousRoom=$activeRoomId closed=$closed " + engine.diagnosticState(),
        )
        sessionSerial++
        activeRoomId = roomId
        closed = false
        paused = false
        pending.clear()
        flushJob?.cancel()
        flushJob = null
        engine.setLiveTraceRoom(roomId)
        clock.reset()
        clock.rebase(0)
        engine.resetForSession(0)
        engine.resume()
        LogHub.i(TAG, "opened session=$sessionSerial room=$roomId " + engine.diagnosticState())
        client?.close()
        val openedSession = sessionSerial
        client = clientFactory.create(
            scope, context, roomId, cookie, uid,
            { items -> onItems(openedSession, items) },
            {},
        )
        client?.start()
        diagnosticJob?.cancel()
        diagnosticJob = scope.launch {
            while (isActive && !closed && activeRoomId == roomId) {
                delay(DIAGNOSTIC_INTERVAL_MS)
                if (isActive && !closed && activeRoomId == roomId) {
                    LogHub.d(
                        TAG,
                        "status session=$sessionSerial room=$roomId paused=$paused " +
                            "enabled=${settings.enabled} pending=${pending.size} " + engine.diagnosticState(),
                    )
                }
            }
        }
    }

    /** 关闭会话并清空屏幕。 */
    fun close() {
        LogHub.i(TAG, "close session=$sessionSerial room=$activeRoomId " + engine.diagnosticState())
        closed = true
        activeRoomId = -1L
        paused = false
        client?.close()
        client = null
        flushJob?.cancel()
        flushJob = null
        diagnosticJob?.cancel()
        diagnosticJob = null
        pending.clear()
        engine.clear()
        clock.reset()
        engine.setLiveTraceRoom(null)
        LogHub.d(TAG, "closed session=$sessionSerial " + engine.diagnosticState())
    }

    /** 播放器恢复播放。 */
    fun onPlaying() {
        if (closed) {
            LogHub.d(TAG, "playing ignored: closed session=$sessionSerial")
            return
        }
        LogHub.d(TAG, "playing session=$sessionSerial room=$activeRoomId before " + engine.diagnosticState())
        paused = false
        clock.rebase(clock.nowMs())
        engine.resume()
        LogHub.d(TAG, "playing session=$sessionSerial after " + engine.diagnosticState())
    }

    /** 播放器暂停；暂停期间的增量直接丢弃。 */
    fun onPaused() {
        if (closed) {
            LogHub.d(TAG, "paused ignored: closed session=$sessionSerial")
            return
        }
        LogHub.d(TAG, "paused session=$sessionSerial room=$activeRoomId before " + engine.diagnosticState())
        paused = true
        pending.clear()
        clock.pause(clock.nowMs())
        engine.pause()
        LogHub.d(TAG, "paused session=$sessionSerial after " + engine.diagnosticState())
    }

    /** 应用显示设置；直播弹幕无智能云屏蔽，强制按关闭过滤。 */
    fun applySettings(newSettings: DanmakuSettings) {
        settings = newSettings
        engine.applySettings(newSettings)
        LogHub.d(
            TAG,
            "settings session=$sessionSerial room=$activeRoomId enabled=${newSettings.enabled} " +
                engine.diagnosticState(),
        )
    }

    /** 释放会话；Activity 销毁时调用。 */
    fun release() {
        close()
    }

    private fun onItems(sourceSession: Long, items: List<DanmakuItem>) {
        if (closed || paused || !settings.enabled) {
            LogHub.d(
                TAG,
                "items dropped at callback source=$sourceSession session=$sessionSerial " +
                    "room=$activeRoomId count=${items.size} " +
                    "closed=$closed paused=$paused enabled=${settings.enabled}",
            )
            return
        }
        // okhttp 回调线程切到主线程，避免与 flush 竞争 pending。
        scope.launch {
            if (closed || paused || !settings.enabled) {
                LogHub.d(
                    TAG,
                    "items dropped on main source=$sourceSession session=$sessionSerial " +
                        "room=$activeRoomId count=${items.size} " +
                        "closed=$closed paused=$paused enabled=${settings.enabled}",
                )
                return@launch
            }
            pending += items
            LogHub.d(
                TAG,
                "items queued source=$sourceSession session=$sessionSerial " +
                    "room=$activeRoomId count=${items.size} " +
                    "pending=${pending.size} " + engine.diagnosticState(),
            )
            if (flushJob?.isActive != true) {
                flushJob = scope.launch {
                    delay(FLUSH_INTERVAL_MS)
                    flush()
                }
            }
        }
    }

    private fun flush() {
        if (closed || paused || pending.isEmpty()) {
            LogHub.d(
                TAG,
                "flush skipped session=$sessionSerial room=$activeRoomId pending=${pending.size} " +
                    "closed=$closed paused=$paused",
            )
            return
        }
        val now = clock.nowMs().toInt()
        val batch = pending.map { it.copy(progressMs = now) }
        pending.clear()
        LogHub.d(
            TAG,
            "flush session=$sessionSerial room=$activeRoomId count=${batch.size} itemTime=$now " +
                engine.diagnosticState(),
        )
        engine.addSegment(batch, AiShieldLevel.OFF)
    }

    private companion object {
        const val TAG = "LiveDanmakuSession"
        const val DIAGNOSTIC_INTERVAL_MS = 5_000L
        /** 攒批间隔，避免高频弹幕逐条触发 DFM 添加。 */
        const val FLUSH_INTERVAL_MS = 100L
    }
}
