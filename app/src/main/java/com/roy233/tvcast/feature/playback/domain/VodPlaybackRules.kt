package com.roy233.tvcast.feature.playback.domain

import com.roy233.tvcast.data.bili.BiliApi
import com.roy233.tvcast.data.bili.VodRecoveryController

/**
 * 点播表示层与恢复判定规则。
 *
 * 全部为纯函数，不含状态与副作用。
 */
object VodPlaybackRules {

    /** 表示层唯一标识，用于判定某档位是否已经尝试过。 */
    fun representationKey(track: BiliApi.Track): String =
        "${track.id}:${track.codecid}:${track.codecs}:${track.mimeType}:${track.width}x${track.height}"

    /** 编码优先级：HEVC > AV1 > H.264 > 其他。 */
    fun videoCodecRank(track: BiliApi.Track): Int = when (track.codecid) {
        12 -> 3
        13 -> 2
        7 -> 1
        else -> 0
    }

    /** 把恢复状态机的失败类型映射为日志与分支使用的稳定标识。 */
    fun recoveryReason(failure: VodRecoveryController.Failure): String = when (failure) {
        VodRecoveryController.Failure.TWO_SEVERE_REBUFFERS -> "two-severe-rebuffers"
        VodRecoveryController.Failure.PERSISTENT_BUFFERING -> "persistent-buffering-5s"
        VodRecoveryController.Failure.OPEN_TIMEOUT -> "player-open-timeout"
    }

    /** 只有播放器错误或已经经过稳定期验证的恢复故障才允许消耗 PCDN 候选。 */
    fun isSubstantiveVodFailure(reason: String): Boolean = when (reason) {
        "player-error", "media3-player-error", "playback-time-stalled-5s",
        "two-severe-rebuffers", "persistent-buffering-5s", "player-open-timeout",
        "representation-fallback-open-failed" -> true
        else -> false
    }
}
