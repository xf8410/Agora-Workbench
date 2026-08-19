package com.newoether.agora.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpreadsheetReaderTest {
    @Test
    fun csv_preservesQuotedDelimitersNewlinesAndEscapedQuotes() {
        val source = "name,note\r\nA,\"x,y\"\r\nB,\"line1\nline2 \"\"ok\"\"\""
        val parsed = SpreadsheetReader.read(
            ByteArrayInputStream(source.toByteArray()), "sample.csv", "text/csv"
        )
        assertEquals(
            "=== Sheet: 1 ===\nname\tnote\nA\tx,y\nB\tline1\\nline2 \"ok\"\n",
            parsed
        )
    }

    @Test
    fun xlsx_readsSheetNamesSharedStringsBooleansAndFormulaCache() {
        val bytes = zipOf(
            "xl/workbook.xml" to """<workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="数据" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/sharedStrings.xml" to """<sst><si><t>名称</t></si><si><t>速度</t></si></sst>""",
            "xl/worksheets/sheet1.xml" to """<worksheet><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row><row r="2"><c r="A2" t="inlineStr"><is><t>小栗帽</t></is></c><c r="B2"><f>SUM(100,20)</f><v>120</v></c><c r="C2" t="b"><v>1</v></c></row></sheetData></worksheet>"""
        )
        val parsed = SpreadsheetReader.read(
            ByteArrayInputStream(bytes), "sample.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        ).orEmpty()
        assertTrue(parsed.contains("=== Sheet: 数据 ==="))
        assertTrue(parsed.contains("名称\t速度"))
        assertTrue(parsed.contains("小栗帽\t=SUM(100,20) [120]\tTRUE"))
    }

    @Test
    fun ods_readsNamedTablesRepeatedColumnsAndFormulaDisplayValue() {
        val bytes = zipOf(
            "content.xml" to """<office:document-content xmlns:office="urn:o" xmlns:table="urn:t" xmlns:text="urn:x"><office:body><office:spreadsheet><table:table table:name="Sheet A"><table:table-row><table:table-cell table:number-columns-repeated="2"><text:p>值</text:p></table:table-cell><table:table-cell table:formula="of:=SUM([.A1:.B1])"><text:p>2</text:p></table:table-cell></table:table-row></table:table></office:spreadsheet></office:body></office:document-content>"""
        )
        val parsed = SpreadsheetReader.read(
            ByteArrayInputStream(bytes), "sample.ods",
            "application/vnd.oasis.opendocument.spreadsheet"
        ).orEmpty()
        assertTrue(parsed.contains("=== Sheet: Sheet A ==="))
        assertTrue(parsed.contains("值\t值\t=SUM([.A1:.B1]) [2]"))
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
