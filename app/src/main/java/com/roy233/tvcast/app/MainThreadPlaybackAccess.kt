package com.roy233.tvcast.app

import android.os.Handler
import android.os.Looper
import com.roy233.tvcast.core.diagnostics.LogHub
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** DLNA 工作线程与要求主线程访问的播放器之间的同步读取边界。 */
class MainThreadPlaybackAccess(
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    fun <T> read(action: () -> T): T {
        if (Looper.myLooper() == handler.looper) return action()
        val task = FutureTask<T> { action() }
        check(handler.post(task)) { "Playback main thread is unavailable" }
        return try {
            task.get(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (failure: Exception) {
            task.cancel(false)
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            LogHub.e(TAG, "playback state read on main thread failed", failure)
            throw failure
        }
    }

    private companion object {
        const val READ_TIMEOUT_SECONDS = 2L
        const val TAG = "SimpleBili"
    }
}
