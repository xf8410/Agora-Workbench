package com.newoether.agora.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SpreadsheetReaderTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun csvPreservesQuotedValues() {
        val parsed = SpreadsheetReader.read(ByteArrayInputStream("a,b\n1,\"x,y\"".toByteArray()), "a.csv", "text/csv")
        assertEquals("=== Sheet: 1 ===\na\tb\n1\tx,y\n", parsed)
    }
    @Test fun xlsxPreservesSheetFormulaAndCachedValue() {
        val bytes = zipOf(
            "xl/workbook.xml" to """<workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="数据" r:id="r1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships><Relationship Id="r1" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/worksheets/sheet1.xml" to """<worksheet><sheetData><row><c r="A1" t="inlineStr"><is><t>值</t></is></c><c r="B1"><f>SUM(1,2)</f><v>3</v></c></row></sheetData></worksheet>"""
        )
        val parsed = SpreadsheetReader.read(ByteArrayInputStream(bytes), "a.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet").orEmpty()
        assertTrue(parsed.contains("=== Sheet: 数据 ===")); assertTrue(parsed.contains("值\t=SUM(1,2) [3]"))
    }
    @Test fun sidecarListsAndReadsExactRange() {
        val file = temp.newFile("book.tsv"); file.writeText("=== Sheet: A ===\nh1\th2\n1\t2\n3\t4\n=== Sheet: B ===\nx\n")
        assertEquals(listOf(SpreadsheetSidecarReader.SheetInfo("A", 3, 2), SpreadsheetSidecarReader.SheetInfo("B", 1, 1)), SpreadsheetSidecarReader.listSheets(file.absolutePath))
        assertEquals(listOf(listOf("2"), listOf("4")), SpreadsheetSidecarReader.readRange(file.absolutePath, "A", 2, 3, 2, 2).rows)
    }
    private fun zipOf(vararg values: Pair<String, String>): ByteArray { val out = ByteArrayOutputStream(); ZipOutputStream(out).use { z -> values.forEach { (name, value) -> z.putNextEntry(ZipEntry(name)); z.write(value.toByteArray()); z.closeEntry() } }; return out.toByteArray() }
}
