package com.roy233.tvcast.feature.history

/** 调用 B 站 Web 播放历史接口及 TV 直播在线心跳接口的端口。 */
interface PlaybackHistoryRepository {
    /** 按 Web 播放心跳协议上报点播播放进度。 */
    fun reportProgress(auth: WebHistoryAuth, report: PlaybackHistoryReport)

    /** 登录后进入直播间时登记观看记录。 */
    fun reportLiveEntry(auth: WebHistoryAuth, roomId: Long)

    /** 发送一次官方 TV 直播心跳，并返回服务端更新的签名状态。 */
    fun reportLiveHeartbeat(accessKey: String, request: LiveHeartbeatRequest): LiveHeartbeatResponse
}
