package com.newoether.agora.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest

/** Opens attachment sources without assuming that every source is a ContentResolver URI. */
object AttachmentSourceReader {
    private const val STORED_TEXT_PREFIX = "@agora-attachment-text:"

    fun open(context: Context, source: String): InputStream? =
        open(source) { uriSource -> context.contentResolver.openInputStream(Uri.parse(uriSource)) }

    /** Plain text keeps the legacy bounded inline representation. Parsed workbooks are written to
     * an immutable content-addressed sidecar and represented by a short reference in Room, so a
     * large workbook cannot be cut by the messages-row persistence budget. */
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
            val parsed = SpreadsheetReader.read(context, source, fileName, mimeType) ?: return null
            val digest = MessageDigest.getInstance("SHA-256").digest(parsed.toByteArray())
                .joinToString("") { "%02x".format(it) }
            val directory = File(context.filesDir, "attachment_text").apply { mkdirs() }
            val target = File(directory, "$digest.tsv")
            if (!target.exists()) target.writeText(parsed)
            return STORED_TEXT_PREFIX + target.absolutePath
        }
        return readText(source, maxChars) { uriSource ->
            context.contentResolver.openInputStream(Uri.parse(uriSource))
        }
    }

    fun resolveStoredText(value: String?): String? {
        if (value == null) return null
        if (!value.startsWith(STORED_TEXT_PREFIX)) return value
        return runCatching { File(value.removePrefix(STORED_TEXT_PREFIX)).readText() }.getOrNull()
    }

    fun storedTextPath(value: String?): String? = value
        ?.takeIf { it.startsWith(STORED_TEXT_PREFIX) }
        ?.removePrefix(STORED_TEXT_PREFIX)

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
