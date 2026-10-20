package com.roy233.tvcast.core.danmaku

import com.roy233.tvcast.data.bili.danmaku.DanmakuItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DanmakuAiShieldTest {

    private fun item(id: Long, weight: Int): DanmakuItem {
        return DanmakuItem(
            id = id,
            progressMs = 0,
            mode = 1,
            fontSize = 25,
            color = 0xFFFFFF,
            midHash = "",
            content = "test-$id",
            weight = weight,
            pool = 0,
            attr = 0,
            ctime = 0L,
        )
    }

    private val items = listOf(
        item(1, 8),
        item(2, 8),
        item(3, 9),
        item(4, 9),
        item(5, 9),
        item(6, 10),
        item(7, 10),
        item(8, 10),
        item(9, 11),
        item(10, 11),
    )

    @Test
    fun offReturnsOriginalList() {
        assertSame(items, DanmakuAiShield.filter(items, AiShieldLevel.OFF))
    }

    @Test
    fun lowHidesLowestTenPercent() {
        val result = DanmakuAiShield.filter(items, AiShieldLevel.LOW)
        assertEquals(9, result.size)
        assertEquals(setOf(1L), items.map { it.id }.toSet() - result.map { it.id }.toSet())
    }

    @Test
    fun mediumHidesLowestThirtyPercent() {
        val result = DanmakuAiShield.filter(items, AiShieldLevel.MEDIUM)
        assertEquals(7, result.size)
        assertEquals(setOf(1L, 2L, 3L), items.map { it.id }.toSet() - result.map { it.id }.toSet())
    }

    @Test
    fun highHidesLowestSixtyPercent() {
        val result = DanmakuAiShield.filter(items, AiShieldLevel.HIGH)
        assertEquals(4, result.size)
        assertEquals(listOf(10, 10, 11, 11), result.map { it.weight }.sorted())
    }

    @Test
    fun tiesUseIdForStableOrder() {
        val tied = listOf(item(2, 8), item(1, 8), item(3, 9), item(4, 9), item(5, 9))
        val result = DanmakuAiShield.filter(tied, AiShieldLevel.LOW)
        assertEquals(4, result.size)
        assertEquals(listOf(2L, 3L, 4L, 5L), result.map { it.id })
    }

    @Test
    fun emptyStaysEmpty() {
        assertEquals(emptyList<DanmakuItem>(), DanmakuAiShield.filter(emptyList(), AiShieldLevel.HIGH))
    }
}
