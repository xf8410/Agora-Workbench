package com.newoether.agora.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelaySectionsTest {

    @Test
    fun `parses sequential sections`() {
        val text = "【架构师】\n先定结构。\n\n【工程师】\n再写实现。"
        val parsed = RelaySections.parse(text)
        assertEquals(listOf("架构师", "工程师"), parsed.sections.map { it.agentName })
        assertEquals("先定结构。", parsed.sections[0].content)
        assertEquals("再写实现。", parsed.sections[1].content)
        assertNull(parsed.errorText)
        assertTrue(parsed.hasRenderableContent)
    }

    @Test
    fun `extracts trailing failure reason`() {
        val text = "【A】\npartial output\n\n[接力失败] 「B」执行失败：timeout"
        val parsed = RelaySections.parse(text)
        assertEquals(listOf("A"), parsed.sections.map { it.agentName })
        assertEquals("partial output", parsed.sections[0].content)
        assertEquals("「B」执行失败：timeout", parsed.errorText)
        assertTrue(parsed.hasRenderableContent)
    }

    @Test
    fun `failure only body has no sections`() {
        val parsed = RelaySections.parse("[接力失败] 未找到「X」的模型提供方")
        assertTrue(parsed.sections.isEmpty())
        assertEquals("未找到「X」的模型提供方", parsed.errorText)
        assertTrue(parsed.hasRenderableContent)
    }

    @Test
    fun `plain text without headers is not relay shaped`() {
        val parsed = RelaySections.parse("just a normal answer\nwith two lines")
        assertTrue(parsed.sections.isEmpty())
        assertNull(parsed.errorText)
        assertFalse(parsed.hasRenderableContent)
    }

    @Test
    fun `section content keeps interior blank lines and formatting`() {
        val text = "【A】\nline1\n\n```kotlin\nval x = 1\n```\nline2"
        val parsed = RelaySections.parse(text)
        assertEquals(1, parsed.sections.size)
        assertTrue(parsed.sections[0].content.contains("```kotlin"))
        assertTrue(parsed.sections[0].content.contains("line2"))
    }

    @Test
    fun `header-like text inside content does not split`() {
        val text = "【A】\n写法：用【】括号包裹名称即可。"
        val parsed = RelaySections.parse(text)
        assertEquals(1, parsed.sections.size)
        assertEquals("A", parsed.sections[0].agentName)
    }

    @Test
    fun `empty headers are skipped`() {
        val text = "【A】\n\n【B】\ncontent"
        val parsed = RelaySections.parse(text)
        assertEquals(listOf("B"), parsed.sections.map { it.agentName })
    }

    @Test
    fun `relay model name detection`() {
        assertTrue(RelaySections.isRelayModelName("接力:架构师+工程师"))
        assertFalse(RelaySections.isRelayModelName("gpt-4o"))
        assertFalse(RelaySections.isRelayModelName(null))
    }
}
