package com.roy233.tvcast.data.bili.subtitle

import com.roy233.tvcast.feature.subtitle.domain.SubtitleContent
import com.roy233.tvcast.feature.subtitle.domain.SubtitleCue
import com.roy233.tvcast.feature.subtitle.domain.SubtitleOrigin
import com.roy233.tvcast.feature.subtitle.domain.SubtitleTrack
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** 验证字幕缓存以内容身份归属。 */
class BiliSubtitleRepositoryTest {

    private val remote = RecordingRemote()
    private val repository = BiliSubtitleRepository(remote)

    @Test
    fun `tracks and bodies of different videos are never shared`() = runBlocking {
        val first = repository.document(CONTENT_A, TRACK_A)
        val second = repository.document(CONTENT_B, TRACK_B)

        assertEquals(listOf("body:1/11", "body:2/22"), remote.requests)
        assertEquals(listOf("a"), first.cues.map { it.text })
        assertEquals(listOf("b"), second.cues.map { it.text })
    }

    @Test
    fun `returning to a video reuses its own cache`() = runBlocking {
        val firstTracks = repository.tracks(CONTENT_A)
        val first = repository.document(CONTENT_A, TRACK_A)
        repository.tracks(CONTENT_B)
        repository.document(CONTENT_B, TRACK_B)

        val againTracks = repository.tracks(CONTENT_A)
        val again = repository.document(CONTENT_A, TRACK_A)

        assertEquals(
            listOf("tracks:1/11", "body:1/11", "tracks:2/22", "body:2/22"),
            remote.requests,
        )
        assertSame(firstTracks, againTracks)
        assertSame(first.cues, again.cues)
    }

    private class RecordingRemote : SubtitleRemote {
        /** 按发起顺序记录的请求。 */
        val requests = mutableListOf<String>()

        override suspend fun fetchTracks(content: SubtitleContent): List<SubtitleTrack> {
            requests += "tracks:${content.aid}/${content.cid}"
            return listOf(if (content == CONTENT_A) TRACK_A else TRACK_B)
        }

        override suspend fun fetchCues(track: SubtitleTrack): List<SubtitleCue> {
            val content = if (track == TRACK_A) CONTENT_A else CONTENT_B
            requests += "body:${content.aid}/${content.cid}"
            return listOf(SubtitleCue(startMs = 0L, endMs = 1_000L, text = track.lan))
        }
    }

    private companion object {
        val CONTENT_A = SubtitleContent(aid = 1L, cid = 11L)
        val CONTENT_B = SubtitleContent(aid = 2L, cid = 22L)
        val TRACK_A =
            SubtitleTrack(
                id = 1L,
                idStr = "1",
                lan = "a",
                lanDoc = "中文",
                url = "https://aisubtitle.hdslb.com/bfs/subtitle/1.json",
                origin = SubtitleOrigin.MANUAL,
                type = 0,
                locked = false,
            )
        val TRACK_B =
            SubtitleTrack(
                id = 2L,
                idStr = "2",
                lan = "b",
                lanDoc = "English",
                url = "https://aisubtitle.hdslb.com/bfs/subtitle/2.json",
                origin = SubtitleOrigin.AI_ASSIST,
                type = 0,
                locked = false,
            )
    }
}
