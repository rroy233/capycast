package com.roy233.tvcast.feature.cast.application

import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction
import com.roy233.tvcast.feature.cast.domain.CastCollection
import com.roy233.tvcast.feature.cast.domain.CastCollectionRepository
import com.roy233.tvcast.feature.cast.domain.CastContent
import com.roy233.tvcast.feature.cast.domain.CollectionVideo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns a temporary opt-in, including metadata jobs and the first HUD-hide latch. */
class CastAutoplaySession(
    private val scope: CoroutineScope,
    private val repository: CastCollectionRepository,
    private val onUpcoming: () -> Unit = {},
    private val prompt: (Boolean) -> Unit,
) {
    private var generation = 0L
    private var lookupRevision = 0L
    private var job: Job? = null
    private var endJob: Job? = null
    private var collection: CastCollection? = null
    private var firstHudHidden = false
    private var playbackStarted = false
    private var promptIssued = false
    private var awaitingAnswer = false
    private var enabled = false
    private var upcomingNotified = false
    private var content: CastContent? = null

    fun start(content: CastContent) {
        clear()
        if (!content.hasUgc) return
        this.content = content
        load(content.aid)
    }

    private fun load(aid: Long) {
        val ticket = generation
        val revision = ++lookupRevision
        job?.cancel()
        job = scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { repository.load(aid) } }
            if (ticket != generation || revision != lookupRevision) return@launch
            result.onFailure {
                LogHub.w(
                    "SimpleBili",
                    "collection lookup failed aid=$aid reason=${LogRedaction.reason(it)}",
                )
            }
            collection = result.getOrNull()
            maybePrompt()
        }
    }

    fun onPlaybackStarted() {
        playbackStarted = true
        maybePrompt()
    }

    fun onFirstHudHidden() {
        firstHudHidden = true
        maybePrompt()
    }

    private fun maybePrompt() {
        if (
            content != null &&
                playbackStarted &&
                firstHudHidden &&
                collection != null &&
                !promptIssued
        ) {
            promptIssued = true
            awaitingAnswer = true
            prompt(true)
        }
    }

    /** Returns true only when this answer newly enables autoplay. */
    fun answer(accept: Boolean): Boolean {
        if (!awaitingAnswer) return false
        awaitingAnswer = false
        enabled = accept
        prompt(false)
        return accept
    }

    /** Notify once per item only when the same collection has a known successor. */
    fun onProgress(aid: Long, cid: Long, positionMs: Long, durationMs: Long, playing: Boolean) {
        if (!enabled || upcomingNotified || !playing || positionMs < 0L || durationMs <= 0L) return
        if (durationMs - positionMs !in 1L..UPCOMING_WINDOW_MS) return
        if (collection?.next(aid, cid) == null) return
        upcomingNotified = true
        onUpcoming()
    }

    /** Wait for in-flight metadata when a short video ends before lookup completes. */
    fun onEnded(aid: Long, cid: Long, complete: (CollectionVideo?) -> Unit): Boolean {
        if (!enabled) return false
        val ticket = generation
        endJob?.cancel()
        endJob = scope.launch {
            job?.join()
            if (ticket == generation) complete(collection?.next(aid, cid))
        }
        return true
    }

    fun advanced(video: CollectionVideo) {
        upcomingNotified = false
        content = CastContent(aid = video.aid, cid = video.cid, bvid = video.bvid)
        collection = null
        load(video.aid)
    }

    fun clear() {
        generation++
        job?.cancel()
        endJob?.cancel()
        job = null
        endJob = null
        content = null
        collection = null
        firstHudHidden = false
        playbackStarted = false
        promptIssued = false
        awaitingAnswer = false
        enabled = false
        upcomingNotified = false
        prompt(false)
    }

    private companion object {
        const val UPCOMING_WINDOW_MS = 5_000L
    }
}
