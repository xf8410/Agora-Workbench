package com.newoether.agora.util

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

object SpreadsheetReader {
    private val extensions = setOf("csv", "tsv", "xlsx", "ods")
    private val mimes = setOf("text/csv", "text/tab-separated-values", "application/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.oasis.opendocument.spreadsheet")
    fun isSpreadsheet(fileName: String?, mimeType: String?): Boolean =
        fileName?.substringAfterLast('.', "")?.lowercase() in extensions || mimeType?.lowercase() in mimes
    fun read(context: Context, source: String, fileName: String?, mimeType: String?): String? =
        AttachmentSourceReader.open(context, source)?.use { read(it, fileName, mimeType) }
    internal fun read(input: InputStream, fileName: String?, mimeType: String?): String? = try {
        val ext = fileName?.substringAfterLast('.', "")?.lowercase()
        when {
            ext == "csv" || mimeType.equals("text/csv", true) || mimeType.equals("application/csv", true) -> delimited(input.readBytes().toString(Charsets.UTF_8), ',')
            ext == "tsv" || mimeType.equals("text/tab-separated-values", true) -> delimited(input.readBytes().toString(Charsets.UTF_8), '\t')
            ext == "xlsx" || mimeType.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", true) -> xlsx(input.readBytes())
            ext == "ods" || mimeType.equals("application/vnd.oasis.opendocument.spreadsheet", true) -> ods(input.readBytes())
            else -> null
        }
    } catch (_: Exception) { null }

    private fun delimited(text: String, delimiter: Char): String {
        val rows = mutableListOf<List<String>>(); var row = mutableListOf<String>(); val cell = StringBuilder(); var quoted = false; var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == delimiter -> { row += cell.toString(); cell.clear() }
                !quoted && (c == '\n' || c == '\r') -> { row += cell.toString(); cell.clear(); rows += row; row = mutableListOf(); if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++ }
                else -> cell.append(c)
            }; i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); rows += row }
        return buildString { append("=== Sheet: 1 ===\n"); rows.forEach { append(it.joinToString("\t", transform = ::escape)).append('\n') } }
    }

    private fun xlsx(bytes: ByteArray): String {
        val zip = unzip(bytes)
        val shared = zip["xl/sharedStrings.xml"]?.let { data -> elements(xml(data).documentElement, "si").map { si -> elements(si, "t").joinToString("") { it.textContent } } } ?: emptyList()
        val rels = mutableMapOf<String, String>()
        zip["xl/_rels/workbook.xml.rels"]?.let { data -> elements(xml(data).documentElement, "Relationship").forEach { rel ->
            var target = rel.getAttribute("Target").removePrefix("/"); if (!target.startsWith("xl/")) target = "xl/$target"; rels[rel.getAttribute("Id")] = normalize(target)
        }}
        val sheets = mutableListOf<Pair<String, String>>()
        zip["xl/workbook.xml"]?.let { data -> elements(xml(data).documentElement, "sheet").forEach { sheet ->
            val rid = sheet.getAttribute("r:id").ifEmpty { (0 until sheet.attributes.length).map { sheet.attributes.item(it) }.firstOrNull { it.localName == "id" }?.nodeValue.orEmpty() }
            rels[rid]?.let { sheets += sheet.getAttribute("name") to it }
        }}
        if (sheets.isEmpty()) zip.keys.filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }.sorted().forEachIndexed { index, path -> sheets += "Sheet ${index + 1}" to path }
        return buildString {
            sheets.forEachIndexed { si, (name, path) ->
                if (si > 0) append('\n'); append("=== Sheet: $name ===\n")
                val data = zip[path] ?: return@forEachIndexed
                elements(xml(data).documentElement, "row").forEach { row ->
                    val cells = mutableMapOf<Int, String>(); var next = 0
                    children(row).filter { it.local() == "c" }.forEach { c ->
                        val letters = c.getAttribute("r").takeWhile { it.isLetter() }; val column = if (letters.isEmpty()) next else columnIndex(letters); next = column + 1
                        val raw = children(c).firstOrNull { it.local() == "v" }?.textContent
                        val formula = children(c).firstOrNull { it.local() == "f" }?.textContent
                        val value = when (c.getAttribute("t")) { "s" -> raw?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty(); "inlineStr" -> elements(c, "t").joinToString("") { it.textContent }; "b" -> if (raw == "1") "TRUE" else "FALSE"; else -> raw.orEmpty() }
                        cells[column] = if (formula != null) "=$formula${if (value.isNotEmpty()) " [$value]" else ""}" else value
                    }
                    val last = cells.keys.maxOrNull() ?: -1; if (last >= 0) append((0..last).joinToString("\t") { escape(cells[it].orEmpty()) }); append('\n')
                }
            }
        }
    }

    private fun ods(bytes: ByteArray): String {
        val data = unzip(bytes)["content.xml"] ?: return ""; val tables = elements(xml(data).documentElement, "table")
        return buildString { tables.forEachIndexed { index, table ->
            if (index > 0) append('\n'); append("=== Sheet: ${table.getAttribute("table:name").ifEmpty { "Sheet ${index + 1}" }} ===\n")
            children(table).filter { it.local() == "table-row" }.forEach { row ->
                val values = mutableListOf<String>(); children(row).filter { it.local() in setOf("table-cell", "covered-table-cell") }.forEach { c ->
                    val repeat = c.getAttribute("table:number-columns-repeated").toIntOrNull() ?: 1; val text = elements(c, "p").joinToString("\n") { it.textContent }; val formula = c.getAttribute("table:formula").removePrefix("of:="); val value = if (formula.isNotEmpty()) "=$formula${if (text.isNotEmpty()) " [$text]" else ""}" else text; repeat(repeat) { values += value }
                }; append(values.joinToString("\t", transform = ::escape)).append('\n')
            }
        }}
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> { val out = linkedMapOf<String, ByteArray>(); ZipInputStream(ByteArrayInputStream(bytes)).use { z -> var e = z.nextEntry; while (e != null) { if (!e.isDirectory) out[normalize(e.name)] = z.readBytes(); z.closeEntry(); e = z.nextEntry } }; return out }
    private fun xml(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }; runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }; runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) } }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    private fun children(n: Node) = (0 until n.childNodes.length).mapNotNull { n.childNodes.item(it) as? Element }
    private fun elements(root: Element, name: String): List<Element> { val ns = root.getElementsByTagNameNS("*", name); if (ns.length > 0) return (0 until ns.length).map { ns.item(it) as Element }; val plain = root.getElementsByTagName(name); return (0 until plain.length).map { plain.item(it) as Element } }
    private fun Element.local() = localName ?: tagName.substringAfter(':')
    private fun normalize(path: String): String { val out = mutableListOf<String>(); path.replace('\\', '/').split('/').forEach { when (it) { "", "." -> Unit; ".." -> if (out.isNotEmpty()) out.removeAt(out.lastIndex); else -> out += it } }; return out.joinToString("/") }
    private fun columnIndex(s: String) = s.uppercase().fold(0) { v, c -> v * 26 + (c - 'A' + 1) } - 1
    private fun escape(s: String) = s.replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n")
}
