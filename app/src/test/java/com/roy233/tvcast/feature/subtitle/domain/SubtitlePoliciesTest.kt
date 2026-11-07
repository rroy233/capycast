package com.roy233.tvcast.feature.subtitle.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证字幕地址、来源分类、排序、命中与默认选择规则。 */
class SubtitlePoliciesTest {

    @Test
    fun `subtitle url is normalized to https and checked against trusted hosts`() {
        assertEquals(
            "https://aisubtitle.hdslb.com/bfs/subtitle/1.json",
            SubtitleUrlPolicy.normalize("//aisubtitle.hdslb.com/bfs/subtitle/1.json"),
        )
        assertEquals(
            "https://i0.hdslb.com/bfs/subtitle/2.json",
            SubtitleUrlPolicy.normalize("http://i0.hdslb.com/bfs/subtitle/2.json"),
        )
        assertTrue(SubtitleUrlPolicy.isTrusted("//aisubtitle.hdslb.com/bfs/subtitle/1.json"))
        assertFalse(SubtitleUrlPolicy.isTrusted("https://evil.example.com/subtitle/1.json"))
        assertFalse(SubtitleUrlPolicy.isTrusted("https://i0.hdslb.com/bfs/other/1.json"))
        assertFalse(SubtitleUrlPolicy.isTrusted(""))
    }

    @Test
    fun `ai status and language labels decide subtitle origin`() {
        assertEquals(
            SubtitleOrigin.AI_ASSIST,
            SubtitleOriginClassifier.classify(aiStatus = 2, aiType = 0, lanDoc = "中文（中国）"),
        )
        assertEquals(
            SubtitleOrigin.AI_EXPOSURE,
            SubtitleOriginClassifier.classify(aiStatus = 1, aiType = 0, lanDoc = "中文（中国）"),
        )
        assertEquals(
            SubtitleOrigin.AI_UNKNOWN,
            SubtitleOriginClassifier.classify(aiStatus = 0, aiType = 1, lanDoc = "英文"),
        )
        assertEquals(
            SubtitleOrigin.AI_UNKNOWN,
            SubtitleOriginClassifier.classify(aiStatus = 0, aiType = 0, lanDoc = "AI 自动生成"),
        )
        assertEquals(
            SubtitleOrigin.MANUAL,
            SubtitleOriginClassifier.classify(aiStatus = 0, aiType = 0, lanDoc = "中文（中国）"),
        )
    }

    @Test
    fun `manual tracks are ordered before ai tracks and chinese before english`() {
        val tracks =
            listOf(
                track(id = 3, lan = "en-US", lanDoc = "English", origin = SubtitleOrigin.MANUAL),
                track(id = 4, lan = "zh-Hans", lanDoc = "中文", origin = SubtitleOrigin.AI_ASSIST),
                track(id = 5, lan = "zh-CN", lanDoc = "中文（中国）", origin = SubtitleOrigin.MANUAL),
                track(id = 6, lan = "en-US", lanDoc = "English", origin = SubtitleOrigin.AI_ASSIST),
            )

        val ordered = SubtitleTrackOrder.order(tracks)

        assertEquals(listOf(5L, 3L, 4L, 6L), ordered.map { it.id })
    }

    @Test
    fun `default track prefers manual subtitles and needs opt in for ai only`() {
        val manual = track(id = 1, lan = "zh-CN", origin = SubtitleOrigin.MANUAL)
        val ai = track(id = 2, lan = "zh-Hans", origin = SubtitleOrigin.AI_ASSIST)

        assertEquals(manual, SubtitleSelection.defaultTrack(listOf(manual, ai), SubtitleSettings()))
        assertNull(SubtitleSelection.defaultTrack(listOf(ai), SubtitleSettings()))
        assertEquals(
            ai,
            SubtitleSelection.defaultTrack(listOf(ai), SubtitleSettings(autoAiEnabled = true)),
        )
        assertNull(
            SubtitleSelection.defaultTrack(listOf(manual), SubtitleSettings(enabled = false)),
        )
    }

    @Test
    fun `cue lookup uses half open intervals and keeps start order`() {
        val cues =
            listOf(
                SubtitleCue(startMs = 0L, endMs = 1_000L, text = "a"),
                SubtitleCue(startMs = 1_000L, endMs = 2_000L, text = "b"),
                SubtitleCue(startMs = 5_000L, endMs = 6_000L, text = "c"),
            )

        assertEquals(listOf("a"), SubtitleCueLookup.resolve(cues, 0L))
        assertEquals(listOf("a"), SubtitleCueLookup.resolve(cues, 999L))
        assertEquals(listOf("b"), SubtitleCueLookup.resolve(cues, 1_000L))
        assertEquals(emptyList<String>(), SubtitleCueLookup.resolve(cues, 2_000L))
        assertEquals(listOf("c"), SubtitleCueLookup.resolve(cues, 5_500L))
        assertEquals(emptyList<String>(), SubtitleCueLookup.resolve(cues, 9_000L))
        assertEquals(emptyList<String>(), SubtitleCueLookup.resolve(emptyList(), 1_000L))
    }

    @Test
    fun `overlapping cues are merged up to the line limit`() {
        val cues =
            listOf(
                SubtitleCue(startMs = 0L, endMs = 2_000L, text = "first"),
                SubtitleCue(startMs = 100L, endMs = 2_000L, text = "second"),
                SubtitleCue(startMs = 200L, endMs = 2_000L, text = "third"),
            )

        assertEquals(listOf("second", "third"), SubtitleCueLookup.resolve(cues, 500L))
    }

    @Test
    fun `track labels mark ai tracks without duplicating explicit markers`() {
        assertEquals(
            "中文（中国）",
            SubtitleLabels.trackTitle(track(id = 1, lanDoc = "中文（中国）")),
        )
        assertEquals(
            "English · AI",
            SubtitleLabels.trackTitle(
                track(id = 2, lanDoc = "English", origin = SubtitleOrigin.AI_ASSIST),
            ),
        )
        assertEquals(
            "AI 自动生成",
            SubtitleLabels.trackTitle(
                track(id = 3, lanDoc = "AI 自动生成", origin = SubtitleOrigin.AI_UNKNOWN),
            ),
        )
    }

    private fun track(
        id: Long,
        lan: String = "zh-CN",
        lanDoc: String = "中文",
        origin: SubtitleOrigin = SubtitleOrigin.MANUAL,
        url: String = "https://aisubtitle.hdslb.com/bfs/subtitle/$id.json",
        locked: Boolean = false,
    ) =
        SubtitleTrack(
            id = id,
            idStr = id.toString(),
            lan = lan,
            lanDoc = lanDoc,
            url = url,
            origin = origin,
            type = 0,
            locked = locked,
        )
}
