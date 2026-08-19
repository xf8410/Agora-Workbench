package com.newoether.agora.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URI
import java.util.zip.ZipInputStream

/** Opens attachment sources without assuming every source is a ContentResolver URI. */
object AttachmentSourceReader {
    fun open(context: Context, source: String): InputStream? = open(source) { uriSource ->
        context.contentResolver.openInputStream(Uri.parse(uriSource))
    }

    fun readText(context: Context, source: String, maxChars: Int): String? =
        if (source.endsWith(".zip", ignoreCase = true)) readZipText(context, source)
        else readText(source, maxChars) { uriSource -> context.contentResolver.openInputStream(Uri.parse(uriSource)) }

    /** Streams every non-directory ZIP entry into a readable text representation. */
    private fun readZipText(context: Context, source: String): String? = try {
        open(context, source)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                val result = StringBuilder()
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        result.append("\n===== ").append(entry.name).append(" =====\n")
                        zip.bufferedReader(Charsets.UTF_8).use { it.copyTo(result) }
                        result.append('\n')
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
                result.toString()
            }
        }
    } catch (_: Exception) { null }

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
