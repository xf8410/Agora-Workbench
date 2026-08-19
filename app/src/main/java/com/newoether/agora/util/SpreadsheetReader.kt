package com.newoether.agora.util

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/** Converts spreadsheet attachments to complete, sheet-delimited TSV text for model input/viewing.
 * The original attachment remains the source of truth; formulas and cached/displayed values are
 * emitted together when both are present. No row, column, sheet, or character limit is applied. */
object SpreadsheetReader {
    private val spreadsheetExtensions = setOf("csv", "tsv", "xlsx", "ods")
    private val spreadsheetMimeTypes = setOf(
        "text/csv",
        "text/tab-separated-values",
        "application/csv",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.oasis.opendocument.spreadsheet",
    )

    fun isSpreadsheet(fileName: String?, mimeType: String?): Boolean {
        val ext = fileName?.substringAfterLast('.', "")?.lowercase()
        return ext in spreadsheetExtensions || mimeType?.lowercase() in spreadsheetMimeTypes
    }

    fun read(context: Context, source: String, fileName: String?, mimeType: String?): String? =
        AttachmentSourceReader.open(context, source)?.use { read(it, fileName, mimeType) }

    internal fun read(input: InputStream, fileName: String?, mimeType: String?): String? = try {
        val ext = fileName?.substringAfterLast('.', "")?.lowercase()
        when {
            ext == "csv" || mimeType.equals("text/csv", true) || mimeType.equals("application/csv", true) ->
                parseDelimited(input.readBytes().toString(Charsets.UTF_8), ',')
            ext == "tsv" || mimeType.equals("text/tab-separated-values", true) ->
                parseDelimited(input.readBytes().toString(Charsets.UTF_8), '\t')
            ext == "ods" || mimeType.equals("application/vnd.oasis.opendocument.spreadsheet", true) ->
                parseOds(input.readBytes())
            ext == "xlsx" || mimeType.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", true) ->
                parseXlsx(input.readBytes())
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    private fun parseDelimited(text: String, delimiter: Char): String {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                    cell.append('"'); i++
                }
                c == '"' -> quoted = !quoted
                !quoted && c == delimiter -> { row.add(cell.toString()); cell.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    row.add(cell.toString()); cell.clear(); rows.add(row); row = mutableListOf()
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
        return buildString {
            append("=== Sheet: 1 ===\n")
            rows.forEach { append(it.joinToString("\t") { value -> escapeTsv(value) }).append('\n') }
        }
    }

    private fun parseXlsx(bytes: ByteArray): String {
        val entries = unzip(bytes)
        val sharedStrings = entries["xl/sharedStrings.xml"]?.let { xml ->
            val doc = parseXml(xml)
            elements(doc.documentElement, "si").map { si ->
                elements(si, "t").joinToString("") { it.textContent }
            }
        } ?: emptyList()

        val relationships = mutableMapOf<String, String>()
        entries["xl/_rels/workbook.xml.rels"]?.let { xml ->
            val root = parseXml(xml).documentElement
            elements(root, "Relationship").forEach { rel ->
                val id = rel.getAttribute("Id")
                var target = rel.getAttribute("Target").removePrefix("/")
                if (!target.startsWith("xl/")) target = "xl/$target"
                relationships[id] = normalizeZipPath(target)
            }
        }

        val sheets = mutableListOf<Pair<String, String>>()
        entries["xl/workbook.xml"]?.let { xml ->
            val root = parseXml(xml).documentElement
            elements(root, "sheet").forEach { sheet ->
                val name = sheet.getAttribute("name")
                val relationId = sheet.getAttribute("r:id").ifEmpty {
                    sheet.attributes?.let { attrs ->
                        (0 until attrs.length).map { attrs.item(it) }
                            .firstOrNull { it.localName == "id" }?.nodeValue
                    } ?: ""
                }
                relationships[relationId]?.let { path -> sheets.add(name to path) }
            }
        }
        if (sheets.isEmpty()) {
            entries.keys.filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
                .sorted().forEachIndexed { index, path -> sheets.add("Sheet ${index + 1}" to path) }
        }

        return buildString {
            sheets.forEachIndexed { sheetIndex, (name, path) ->
                if (sheetIndex > 0) append('\n')
                append("=== Sheet: ").append(name).append(" ===\n")
                val xml = entries[path] ?: return@forEachIndexed
                val root = parseXml(xml).documentElement
                elements(root, "row").forEach { rowElement ->
                    val cells = mutableMapOf<Int, String>()
                    var sequentialColumn = 0
                    childElements(rowElement).filter { it.localNameOrName() == "c" }.forEach { cell ->
                        val reference = cell.getAttribute("r")
                        val column = reference.takeWhile { it.isLetter() }.let {
                            if (it.isEmpty()) sequentialColumn else columnIndex(it)
                        }
                        sequentialColumn = column + 1
                        val type = cell.getAttribute("t")
                        val formula = childElements(cell).firstOrNull { it.localNameOrName() == "f" }?.textContent
                        val raw = childElements(cell).firstOrNull { it.localNameOrName() == "v" }?.textContent
                        val inline = elements(cell, "t").joinToString("") { it.textContent }
                        val value = when (type) {
                            "s" -> raw?.toIntOrNull()?.let { sharedStrings.getOrNull(it) }.orEmpty()
                            "inlineStr" -> inline
                            "b" -> if (raw == "1") "TRUE" else "FALSE"
                            else -> raw.orEmpty()
                        }
                        cells[column] = if (formula != null) "=$formula${if (value.isNotEmpty()) " [$value]" else ""}" else value
                    }
                    val last = cells.keys.maxOrNull() ?: -1
                    if (last >= 0) append((0..last).joinToString("\t") { escapeTsv(cells[it].orEmpty()) })
                    append('\n')
                }
            }
        }
    }

    private fun parseOds(bytes: ByteArray): String {
        val content = unzip(bytes)["content.xml"] ?: return ""
        val root = parseXml(content).documentElement
        val tables = elements(root, "table")
        return buildString {
            tables.forEachIndexed { index, table ->
                if (index > 0) append('\n')
                val name = table.getAttribute("table:name").ifEmpty { "Sheet ${index + 1}" }
                append("=== Sheet: ").append(name).append(" ===\n")
                childElements(table).filter { it.localNameOrName() == "table-row" }.forEach { row ->
                    val values = mutableListOf<String>()
                    childElements(row).filter { it.localNameOrName() in setOf("table-cell", "covered-table-cell") }.forEach { cell ->
                        val repeated = cell.getAttribute("table:number-columns-repeated").toIntOrNull() ?: 1
                        val text = elements(cell, "p").joinToString("\n") { it.textContent }
                        val formula = cell.getAttribute("table:formula").removePrefix("of:=")
                        val value = if (formula.isNotEmpty()) "=$formula${if (text.isNotEmpty()) " [$text]" else ""}" else text
                        repeat(repeated) { values.add(value) }
                    }
                    append(values.joinToString("\t") { escapeTsv(it) }).append('\n')
                }
            }
        }
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) result[normalizeZipPath(entry.name)] = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return result
    }

    private fun parseXml(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))

    private fun childElements(node: Node): List<Element> =
        (0 until node.childNodes.length).mapNotNull { node.childNodes.item(it) as? Element }

    private fun elements(root: Element, localName: String): List<Element> {
        val namespaced = root.getElementsByTagNameNS("*", localName)
        if (namespaced.length > 0) return (0 until namespaced.length).map { namespaced.item(it) as Element }
        val plain = root.getElementsByTagName(localName)
        return (0 until plain.length).map { plain.item(it) as Element }
    }

    private fun Element.localNameOrName(): String = localName ?: tagName.substringAfter(':')
    private fun normalizeZipPath(path: String): String {
        val out = mutableListOf<String>()
        path.replace('\\', '/').split('/').forEach {
            when (it) { "", "." -> Unit; ".." -> if (out.isNotEmpty()) out.removeAt(out.lastIndex); else -> out.add(it) }
        }
        return out.joinToString("/")
    }
    private fun columnIndex(letters: String): Int = letters.uppercase().fold(0) { value, c -> value * 26 + (c - 'A' + 1) } - 1
    private fun escapeTsv(value: String): String = value.replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n")
}
