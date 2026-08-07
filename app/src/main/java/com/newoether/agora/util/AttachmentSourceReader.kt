package com.newoether.agora.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URI

object AttachmentSourceReader {
    fun open(context: Context, source: String): InputStream? = open(source) { context.contentResolver.openInputStream(Uri.parse(it)) }
    fun readText(context: Context, source: String, maxChars: Int): String? {
        val uri = runCatching { Uri.parse(source) }.getOrNull()
        val mime = uri?.let { runCatching { context.contentResolver.getType(it) }.getOrNull() }
        val name = when { source.startsWith("file:", true) -> runCatching { File(URI(source)).name }.getOrNull(); File(source).isAbsolute -> File(source).name; uri != null -> FileValidator.resolveFileName(context, uri) ?: uri.lastPathSegment; else -> null }
        if (SpreadsheetReader.isSpreadsheet(name, mime)) return SpreadsheetReader.read(context, source, name, mime)
        return readText(source, maxChars) { context.contentResolver.openInputStream(Uri.parse(it)) }
    }
    internal fun open(source: String, uriOpener: (String) -> InputStream?): InputStream? = try { when { source.startsWith("file:", true) -> FileInputStream(File(URI(source))); File(source).isAbsolute -> FileInputStream(source); else -> uriOpener(source) } } catch (_: Exception) { null }
    internal fun readText(source: String, maxChars: Int, uriOpener: (String) -> InputStream?): String? { require(maxChars >= 0); return try { open(source, uriOpener)?.bufferedReader()?.use { reader -> val out = StringBuilder(minOf(maxChars, 8192)); val buffer = CharArray(minOf(maxChars.coerceAtLeast(1), 8192)); while (out.length < maxChars) { val count = reader.read(buffer, 0, minOf(buffer.size, maxChars - out.length)); if (count <= 0) break; out.append(buffer, 0, count) }; out.toString() } } catch (_: Exception) { null } }
}
