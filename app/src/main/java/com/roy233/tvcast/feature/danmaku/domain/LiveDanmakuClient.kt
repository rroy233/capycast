package com.roy233.tvcast.feature.danmaku.domain

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/** 直播弹幕长连接端口。 */
interface LiveDanmakuClient {
    /** 连接生命周期状态，供宿主记录诊断信息。 */
    enum class State { CONNECTING, CONNECTED, AUTHENTICATED, DISCONNECTED, FAILED }

    /** 开始连接；已连接时调用会先断开旧连接。 */
    fun start()

    /** 关闭连接并停止心跳与重连。 */
    fun close()

    /** 立即发送一次心跳。 */
    fun heartbeat()
}

/** 创建直播弹幕客户端实例的工厂端口。 */
fun interface LiveDanmakuClientFactory {
    fun create(
        scope: CoroutineScope,
        context: Context,
        roomId: Long,
        cookie: String,
        uid: Long,
        onItems: (List<DanmakuItem>) -> Unit,
        onState: (LiveDanmakuClient.State) -> Unit,
    ): LiveDanmakuClient
}
