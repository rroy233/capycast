package com.roy233.tvcast.ui.tv.document

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownDocumentTest {

    @Test
    fun `parses long document blocks independently of their feature`() {
        val blocks =
            parseMarkdownDocument(
                """
                ## 标题
                正文
                - 条目
                1. 顺序条目
                ---
                """
                    .trimIndent(),
            )

        assertEquals(
            listOf(
                MarkdownBlockKind.HEADING,
                MarkdownBlockKind.TEXT,
                MarkdownBlockKind.BULLET,
                MarkdownBlockKind.ORDERED,
                MarkdownBlockKind.DIVIDER,
            ),
            blocks.map(MarkdownBlock::kind),
        )
        assertEquals("顺序条目", blocks[3].text.text)
        assertEquals("1.", blocks[3].prefix)
    }

    @Test
    fun `uses first meaningful line as document preview`() {
        assertEquals("更新内容", markdownDocumentPreview("\n# 更新内容\n- 修复问题"))
    }
}
