package com.roy233.tvcast.core.lifecycle

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** 前台与设备交互状态共同控制播放和投屏；代次使跨线程排队的旧命令失效。 */
class ForegroundAvailability {
    private data class State(val available: Boolean, val generation: Long)

    enum class Transition {
        AVAILABLE,
        UNAVAILABLE,
        UNCHANGED,
    }

    private var resumed = false
    private var interactive = false
    private var topResumed = true
    private var eligible = false
    private val state = MutableStateFlow(State(false, 0L))

    @Synchronized
    fun setResumed(value: Boolean): Transition {
        resumed = value
        return update()
    }

    @Synchronized
    fun setInteractive(value: Boolean): Transition {
        interactive = value
        return update()
    }

    @Synchronized
    fun setTopResumed(value: Boolean): Transition {
        topResumed = value
        return update()
    }

    val isAvailable: Boolean
        get() = state.value.available

    val isEligible: Boolean
        @Synchronized get() = eligible

    /** Only publish availability after the foreground player has been restored. */
    @Synchronized
    fun activate(): Boolean {
        if (!eligible) return false
        val previous = state.value
        if (!previous.available) state.value = State(true, previous.generation + 1L)
        return true
    }

    fun capture(): Long? = state.value.let { if (it.available) it.generation else null }

    fun isCurrent(generation: Long): Boolean =
        state.value.let { it.available && it.generation == generation }

    /** Atomically admit a short operation against the current foreground generation. */
    @Synchronized
    fun <T> ifCurrent(generation: Long, action: () -> T): T? =
        if (isCurrent(generation)) action() else null

    suspend fun awaitAvailable() {
        state.first { it.available }
    }

    private fun update(): Transition {
        val next = resumed && interactive && topResumed
        if (next == eligible) return Transition.UNCHANGED
        eligible = next
        if (!next) {
            val previous = state.value
            if (previous.available) state.value = State(false, previous.generation + 1L)
        }
        return if (next) Transition.AVAILABLE else Transition.UNAVAILABLE
    }
}
