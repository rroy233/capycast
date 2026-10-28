package com.roy233.tvcast.feature.playback.domain

/** 统计直播播放期间连续重缓冲次数。 */
class LivePlaybackHealth {
    private var hasPlayed = false
    private var buffering = false
    private var rebufferCount = 0

    fun onPlaying() {
        hasPlayed = true
        buffering = false
    }

    fun onBuffering(isBuffering: Boolean): Boolean {
        if (isBuffering && !buffering && hasPlayed) rebufferCount++
        buffering = isBuffering
        if (rebufferCount < REBUFFER_SWITCH_THRESHOLD) return false
        rebufferCount = 0
        return true
    }

    fun reset() {
        hasPlayed = false
        buffering = false
        rebufferCount = 0
    }

    private companion object {
        const val REBUFFER_SWITCH_THRESHOLD = 2
    }
}

/** 根据播放位置、网络读取和视频帧判断直播是否停滞。 */
class LivePlaybackStallWatchdog(
    private val timeoutMs: Long = STALL_TIMEOUT_MS,
) {
    private var lastProgressAtMs = 0L
    private var lastPositionMs = -1L
    private var lastReadBytes = -1L
    private var lastDemuxReadBytes = -1L
    private var lastDecodedFrames = -1L
    private var lastDisplayedPictures = -1L
    private var failureReported = false

    fun observe(
        positionMs: Long,
        readBytes: Long,
        demuxReadBytes: Long,
        decodedVideoFrames: Long,
        displayedPictures: Long,
        nowMs: Long,
        playing: Boolean,
        buffering: Boolean,
    ): Boolean {
        if (!playing || buffering) {
            updateSample(positionMs, readBytes, demuxReadBytes, decodedVideoFrames, displayedPictures)
            lastProgressAtMs = nowMs
            failureReported = false
            return false
        }
        val progressed = isAdvanced(positionMs, lastPositionMs) ||
            isAdvanced(readBytes, lastReadBytes) ||
            isAdvanced(demuxReadBytes, lastDemuxReadBytes) ||
            isAdvanced(decodedVideoFrames, lastDecodedFrames) ||
            isAdvanced(displayedPictures, lastDisplayedPictures)
        updateSample(positionMs, readBytes, demuxReadBytes, decodedVideoFrames, displayedPictures)
        if (lastProgressAtMs == 0L || progressed) {
            lastProgressAtMs = nowMs
            failureReported = false
            return false
        }
        if (failureReported || nowMs - lastProgressAtMs < timeoutMs) return false
        failureReported = true
        return true
    }

    fun reset() {
        lastProgressAtMs = 0L
        lastPositionMs = -1L
        lastReadBytes = -1L
        lastDemuxReadBytes = -1L
        lastDecodedFrames = -1L
        lastDisplayedPictures = -1L
        failureReported = false
    }

    private fun updateSample(positionMs: Long, readBytes: Long, demuxReadBytes: Long, decodedVideoFrames: Long, displayedPictures: Long) {
        if (positionMs >= 0L) lastPositionMs = positionMs
        if (readBytes >= 0L) lastReadBytes = readBytes
        if (demuxReadBytes >= 0L) lastDemuxReadBytes = demuxReadBytes
        if (decodedVideoFrames >= 0L) lastDecodedFrames = decodedVideoFrames
        if (displayedPictures >= 0L) lastDisplayedPictures = displayedPictures
    }

    private fun isAdvanced(current: Long, previous: Long): Boolean = current >= 0L && previous >= 0L && current > previous

    private companion object { const val STALL_TIMEOUT_MS = 5_000L }
}
