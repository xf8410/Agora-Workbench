package com.newoether.agora.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URI

/** Opens attachment sources without assuming every source is a ContentResolver URI. */
object AttachmentSourceReader {
    fun open(context: Context, source: String): InputStream? = open(source) { uriSource ->
        context.contentResolver.openInputStream(Uri.parse(uriSource))
    }

    /** Reads plain text and converts supported spreadsheets to sheet-delimited TSV.
     * Spreadsheet conversion keeps every parsed row, column and sheet; maxChars remains the
     * legacy plain-text limit and is not applied while parsing a workbook. */
    fun readText(context: Context, source: String, maxChars: Int): String? {
        val uri = runCatching { Uri.parse(source) }.getOrNull()
        val mimeType = uri?.let { runCatching { context.contentResolver.getType(it) }.getOrNull() }
        val fileName = when {
            source.startsWith("file:", ignoreCase = true) -> runCatching { File(URI(source)).name }.getOrNull()
            File(source).isAbsolute -> File(source).name
            uri != null -> FileValidator.resolveFileName(context, uri) ?: uri.lastPathSegment
            else -> null
        }
        if (SpreadsheetReader.isSpreadsheet(fileName, mimeType)) {
            return SpreadsheetReader.read(context, source, fileName, mimeType)
        }
        return readText(source, maxChars) { uriSource ->
            context.contentResolver.openInputStream(Uri.parse(uriSource))
        }
    }

    /** ZIP archives are opaque simulator/workspace attachments. Do not expand their entries into
     * attachmentMeta: that metadata is stored in a Room TEXT column and loading it can overflow
     * Android's CursorWindow. The original URI remains available for whole-file upload/open. */
    internal fun open(source: String, uriOpener: (String) -> InputStream?): InputStream? = try {
        when {
            source.startsWith("file:", ignoreCase = true) -> FileInputStream(File(URI(source)))
            File(source).isAbsolute -> FileInputStream(source)
            else -> uriOpener(source)
        }
    } catch (_: Exception) { null }

    internal fun readText(source: String, maxChars: Int, uriOpener: (String) -> InputStream?): String? {
        require(maxChars >= 0)
        return try {
            open(source, uriOpener)?.bufferedReader()?.use { reader ->
                val result = StringBuilder(minOf(maxChars, 8_192))
                val buffer = CharArray(minOf(maxChars.coerceAtLeast(1), 8_192))
                while (result.length < maxChars) {
                    val read = reader.read(buffer, 0, minOf(buffer.size, maxChars - result.length))
                    if (read <= 0) break
                    result.append(buffer, 0, read)
                }
                result.toString()
            }
        } catch (_: Exception) { null }
    }
}
