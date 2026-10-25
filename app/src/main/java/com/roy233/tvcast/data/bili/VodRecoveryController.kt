package com.roy233.tvcast.data.bili

/**
 * Judges whether a VOD playback interruption represents normal startup work or a route failure.
 *
 * libVLC reports buffering while opening media and while applying an initial seek. Those events
 * are expected and must not consume CDN candidates. Only buffering that begins after verified
 * forward playback participates in automatic recovery.
 */
class VodRecoveryController {

    enum class Phase {
        IDLE,
        OPENING,
        SEEKING,
        STABILIZING,
        STABLE,
        BUFFERING,
        SWITCHING,
    }

    enum class Failure {
        TWO_SEVERE_REBUFFERS,
        PERSISTENT_BUFFERING,
        OPEN_TIMEOUT,
    }

    data class BufferingUpdate(
        val phase: Phase,
        val countedRebuffer: Boolean = false,
        val durationMs: Long = 0L,
        val ignored: Boolean = false,
        val failure: Failure? = null,
    )

    data class ProgressUpdate(
        val phase: Phase,
        val becameStable: Boolean = false,
    )

    var generation: Long = 0L
        private set
    var phase: Phase = Phase.IDLE
        private set
    var rebufferCount: Int = 0
        private set

    private var openedAtMs = 0L
    private var stableCandidateAtMs = 0L
    private var stableCandidatePositionMs = 0L
    private var bufferingStartedAtMs = 0L
    private var bufferingEligible = false
    private var persistentBufferReported = false
    private var lastRebufferAtMs = 0L
    private var openTimeoutReported = false
    private var awaitingInitialPlaying = false

    /** Starts a new player-open generation and invalidates every earlier buffering episode. */
    fun onOpening(positionMs: Long, nowMs: Long): Long {
        generation++
        phase = Phase.OPENING
        openedAtMs = nowMs
        stableCandidateAtMs = nowMs
        stableCandidatePositionMs = positionMs.coerceAtLeast(0L)
        bufferingStartedAtMs = 0L
        bufferingEligible = false
        persistentBufferReported = false
        openTimeoutReported = false
        awaitingInitialPlaying = true
        return generation
    }

    /** The backend accepted the current media; startup buffering remains ineligible. */
    fun onPlaying(positionMs: Long, nowMs: Long) {
        awaitingInitialPlaying = false
        phase = Phase.STABILIZING
        stableCandidateAtMs = nowMs
        stableCandidatePositionMs = positionMs.coerceAtLeast(0L)
        bufferingStartedAtMs = 0L
        bufferingEligible = false
    }

    /** A user or automatic seek creates a new startup-like period. */
    fun onSeek(positionMs: Long, nowMs: Long) {
        phase = Phase.SEEKING
        stableCandidateAtMs = nowMs
        stableCandidatePositionMs = positionMs.coerceAtLeast(0L)
        bufferingStartedAtMs = 0L
        bufferingEligible = false
    }

    /** Records forward progress and opens automatic recovery only after stable playback. */
    fun onProgress(positionMs: Long, activelyPlaying: Boolean, nowMs: Long): ProgressUpdate {
        if (!activelyPlaying) return ProgressUpdate(phase)
        val position = positionMs.coerceAtLeast(0L)
        if (phase == Phase.SEEKING) {
            phase = Phase.STABILIZING
            stableCandidateAtMs = nowMs
            stableCandidatePositionMs = position
        }
        if (phase == Phase.STABILIZING &&
            nowMs - stableCandidateAtMs >= MIN_STABLE_PLAYBACK_MS &&
            position - stableCandidatePositionMs >= MIN_STABLE_PROGRESS_MS
        ) {
            phase = Phase.STABLE
            if (lastRebufferAtMs == 0L || nowMs - lastRebufferAtMs > REBUFFER_WINDOW_MS) {
                rebufferCount = 0
            }
            return ProgressUpdate(phase, becameStable = true)
        }
        if (phase == Phase.STABLE && lastRebufferAtMs > 0L && nowMs - lastRebufferAtMs > REBUFFER_WINDOW_MS) {
            rebufferCount = 0
        }
        return ProgressUpdate(phase)
    }

    /**
     * Accepts libVLC's coarse buffering state. Opening and seeking buffering is retained for
     * diagnostics but cannot cause a route change.
     */
    fun onBufferingChanged(buffering: Boolean, nowMs: Long): BufferingUpdate {
        if (buffering) {
            if (bufferingStartedAtMs != 0L) return BufferingUpdate(phase)
            bufferingStartedAtMs = nowMs
            bufferingEligible = phase == Phase.STABLE
            if (bufferingEligible) phase = Phase.BUFFERING
            return BufferingUpdate(phase, ignored = !bufferingEligible)
        }

        if (bufferingStartedAtMs == 0L) return BufferingUpdate(phase)
        val durationMs = (nowMs - bufferingStartedAtMs).coerceAtLeast(0L)
        val eligible = bufferingEligible
        bufferingStartedAtMs = 0L
        bufferingEligible = false
        persistentBufferReported = false
        if (!eligible) return BufferingUpdate(phase, durationMs = durationMs, ignored = true)

        phase = Phase.STABILIZING
        stableCandidateAtMs = nowMs
        if (durationMs < MIN_SEVERE_REBUFFER_MS) {
            return BufferingUpdate(phase, durationMs = durationMs)
        }
        if (lastRebufferAtMs == 0L || nowMs - lastRebufferAtMs > REBUFFER_WINDOW_MS) {
            rebufferCount = 0
        }
        lastRebufferAtMs = nowMs
        rebufferCount++
        return BufferingUpdate(
            phase = phase,
            countedRebuffer = true,
            durationMs = durationMs,
            failure = if (rebufferCount >= REBUFFER_SWITCH_THRESHOLD) Failure.TWO_SEVERE_REBUFFERS else null,
        )
    }

    /** Reports a genuinely stuck opening or buffering episode once. */
    fun onTick(nowMs: Long): Failure? = when {
        awaitingInitialPlaying && phase == Phase.OPENING &&
            !openTimeoutReported && nowMs - openedAtMs >= OPEN_TIMEOUT_MS -> {
            openTimeoutReported = true
            Failure.OPEN_TIMEOUT
        }
        phase == Phase.BUFFERING && !persistentBufferReported &&
            nowMs - bufferingStartedAtMs >= PERSISTENT_BUFFER_MS -> {
            persistentBufferReported = true
            Failure.PERSISTENT_BUFFERING
        }
        else -> null
    }

    /** Suppresses buffering events while a replacement route is being opened. */
    fun onSwitching() {
        phase = Phase.SWITCHING
        bufferingStartedAtMs = 0L
        bufferingEligible = false
    }

    fun snapshot(): String =
        "generation=$generation phase=$phase rebuffers=$rebufferCount bufferStarted=$bufferingStartedAtMs"

    private companion object {
        const val MIN_STABLE_PLAYBACK_MS = 2_000L
        const val MIN_STABLE_PROGRESS_MS = 1_000L
        const val MIN_SEVERE_REBUFFER_MS = 1_000L
        const val REBUFFER_WINDOW_MS = 30_000L
        const val PERSISTENT_BUFFER_MS = 5_000L
        const val OPEN_TIMEOUT_MS = 12_000L
        const val REBUFFER_SWITCH_THRESHOLD = 2
    }
}
