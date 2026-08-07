package com.newoether.agora.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class SpreadsheetViewerTest {
    @Test
    fun parseSpreadsheetPreview_preservesSheetOrderRowsAndEscapedCells() {
        val content = """
            === Sheet: basic_effect ===
            id	effect
            1	line1\nline2
            === Sheet: check_point ===
            id	value
            2	15
        """.trimIndent()

        val sheets = parseSpreadsheetPreview(content)

        assertEquals(listOf("basic_effect", "check_point"), sheets.map { it.name })
        assertEquals(listOf("id", "effect"), sheets[0].rows[0])
        assertEquals("line1\nline2", sheets[0].rows[1][1])
        assertEquals(listOf("2", "15"), sheets[1].rows[1])
    }
}
