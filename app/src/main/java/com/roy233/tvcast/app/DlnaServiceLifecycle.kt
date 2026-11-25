package com.roy233.tvcast.app

import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.lifecycle.ForegroundAvailability
import com.roy233.tvcast.platform.dlna.DlnaRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** 串行管理 DLNA 网络资源；主线程只发布期望状态。 */
class DlnaServiceLifecycle
internal constructor(
    private val start: () -> Boolean,
    private val stop: () -> Unit,
    private val foreground: ForegroundAvailability,
) {
    constructor(
        renderer: DlnaRenderer,
        foreground: ForegroundAvailability,
    ) : this(renderer::start, renderer::stop, foreground)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<Long?>(Channel.UNLIMITED)
    @Volatile private var acceptingGeneration: Long? = null

    fun captureGeneration(): Long? = acceptingGeneration?.takeIf(foreground::isCurrent)

    init {
        scope.launch {
            try {
                for (generation in requests) {
                    runCatching {
                        if (generation != null && foreground.isCurrent(generation)) {
                            if (start() && foreground.isCurrent(generation)) {
                                acceptingGeneration = generation
                            } else {
                                acceptingGeneration = null
                                stop()
                            }
                        } else {
                            acceptingGeneration = null
                            stop()
                        }
                    }
                        .onFailure { LogHub.e(TAG, "DLNA lifecycle transition failed", it) }
                }
            } finally {
                runCatching { stop() }
                    .onFailure { LogHub.e(TAG, "DLNA lifecycle shutdown failed", it) }
                scope.cancel()
            }
        }
    }

    fun setActive(active: Boolean) {
        if (!active) acceptingGeneration = null
        val generation = if (active) foreground.capture() else null
        check(requests.trySend(generation).isSuccess) { "DLNA lifecycle is closed" }
    }

    fun close() {
        acceptingGeneration = null
        requests.close()
    }

    private companion object {
        const val TAG = "DlnaLifecycle"
    }
}
