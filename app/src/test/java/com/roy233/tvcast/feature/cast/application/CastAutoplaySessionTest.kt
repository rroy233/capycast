package com.roy233.tvcast.feature.cast.application

import com.roy233.tvcast.feature.cast.domain.CastCollection
import com.roy233.tvcast.feature.cast.domain.CastContent
import com.roy233.tvcast.feature.cast.domain.CollectionVideo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class CastAutoplaySessionTest {
    private val first = CollectionVideo(1, "BV1", 10, "one")
    private val second = CollectionVideo(2, "BV2", 20, "two")
    private val collection = CastCollection(listOf(first, second), 1, listOf(10, 11))

    @Test
    fun `pages precede next collection video and unknown pages cannot advance`() {
        assertEquals(first.copy(cid = 11), collection.next(1, 10))
        assertEquals(second, collection.next(1, 11))
        assertNull(collection.next(1, 99))
        assertNull(collection.next(2, 20))
    }

    @Test
    fun `first hud hide and metadata are a one time rendezvous`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val response = CompletableDeferred<CastCollection?>()
            val prompts = mutableListOf<Boolean>()
            val session = CastAutoplaySession(scope, { response.await() }, prompt = prompts::add)
            session.start(CastContent(aid = 1, cid = 10))
            session.onFirstHudHidden()
            assertFalse(prompts.contains(true))
            session.onPlaybackStarted()
            response.complete(collection)
            withTimeout(2000) { while (!prompts.contains(true)) delay(5) }
            session.onFirstHudHidden()
            assertEquals(1, prompts.count { it })
            assertTrue(session.answer(true))
            assertFalse(session.answer(true))
            val next = CompletableDeferred<CollectionVideo?>()
            assertTrue(session.onEnded(1, 10) { next.complete(it) })
            assertEquals(first.copy(cid = 11), withTimeout(2000) { next.await() })
            session.advanced(first.copy(cid = 11))
            session.onFirstHudHidden()
            assertEquals(1, prompts.count { it })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `replacement cancels waiting continuation`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val secondLoad = CompletableDeferred<CastCollection?>()
            var calls = 0
            val prompts = mutableListOf<Boolean>()
            val session =
                CastAutoplaySession(
                    scope,
                    { if (++calls == 1) collection else secondLoad.await() },
                    prompt = prompts::add,
                )
            session.start(CastContent(aid = 1, cid = 10))
            session.onFirstHudHidden()
            session.onPlaybackStarted()
            withTimeout(2000) { while (!prompts.contains(true)) delay(5) }
            session.answer(true)
            session.advanced(first.copy(cid = 11))
            val completed = CompletableDeferred<CollectionVideo?>()
            assertTrue(session.onEnded(1, 11) { completed.complete(it) })
            session.clear()
            secondLoad.complete(collection)
            delay(50)
            assertFalse(completed.isCompleted)
            assertFalse(session.onEnded(1, 11) {})
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `upcoming notification is once per playable successor and only while playing`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val prompts = mutableListOf<Boolean>()
                var notifications = 0
                val session =
                    CastAutoplaySession(
                        scope,
                        { aid ->
                            if (aid == 1L) collection
                            else CastCollection(listOf(first, second), 2, listOf(20))
                        },
                        onUpcoming = { notifications++ },
                        prompt = prompts::add,
                    )
                session.start(CastContent(aid = 1, cid = 10))
                session.onFirstHudHidden()
                session.onPlaybackStarted()
                withTimeout(2000) { while (!prompts.contains(true)) delay(5) }
                session.onProgress(1, 10, 56_000, 60_000, true)
                assertEquals(0, notifications)
                session.answer(true)
                session.onProgress(1, 10, 54_999, 60_000, true)
                session.onProgress(1, 10, 55_000, 60_000, false)
                session.onProgress(1, 10, 60_000, 60_000, true)
                assertEquals(0, notifications)
                session.onProgress(1, 10, 55_000, 60_000, true)
                session.onProgress(1, 10, 58_000, 60_000, true)
                assertEquals(1, notifications)
                session.advanced(first.copy(cid = 11))
                withTimeout(2000) {
                    while (notifications == 1) {
                        session.onProgress(1, 11, 57_000, 60_000, true)
                        delay(5)
                    }
                }
                assertEquals(2, notifications)
                session.advanced(second)
                delay(30)
                session.onProgress(2, 20, 59_000, 60_000, true)
                assertEquals(2, notifications)
                session.clear()
                session.onProgress(1, 10, 59_000, 60_000, true)
                assertEquals(2, notifications)
            } finally {
                scope.cancel()
            }
        }
}
