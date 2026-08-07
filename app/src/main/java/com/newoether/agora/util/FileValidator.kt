package com.newoether.agora.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

object FileValidator {
    enum class Error { UNKNOWN_TYPE, UNSUPPORTED_TYPE, TOO_LARGE }
    private val mimePrefixes = setOf("text/", "application/json", "application/xml", "application/yaml", "application/pdf", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.oasis.opendocument.spreadsheet", "application/csv")
    private val extensions = setOf("txt", "md", "markdown", "json", "xml", "yaml", "yml", "csv", "tsv", "xlsx", "ods", "pdf")
    private const val MAX_SIZE = 20L * 1024 * 1024
    data class Result(val valid: Boolean, val error: Error? = null, val mimeType: String? = null)
    fun validate(context: Context, uri: Uri): Result {
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val extension = resolveFileName(context, uri)?.substringAfterLast('.', "")?.lowercase()
        val extensionAllowed = extension != null && extension in extensions
        if (mime == null && !extensionAllowed) return Result(false, Error.UNKNOWN_TYPE)
        val mimeAllowed = mime != null && mimePrefixes.any { mime.startsWith(it) }
        if (!mimeAllowed && !extensionAllowed) return Result(false, Error.UNSUPPORTED_TYPE, mime)
        val size = resolveFileSize(context, uri)
        if (size != null && size > MAX_SIZE && mime != "application/pdf") return Result(false, Error.TOO_LARGE, mime)
        return Result(true, mimeType = mime)
    }
    fun resolveMimeType(context: Context, uriString: String) = runCatching { context.contentResolver.getType(Uri.parse(uriString)) }.getOrNull()
    fun resolveFileName(context: Context, uri: Uri): String? = query(context, uri, OpenableColumns.DISPLAY_NAME) { it.getString(it.getColumnIndex(OpenableColumns.DISPLAY_NAME)) }
    fun resolveFileSize(context: Context, uri: Uri): Long? = query(context, uri, OpenableColumns.SIZE) { it.getLong(it.getColumnIndex(OpenableColumns.SIZE)) }
    private fun <T> query(context: Context, uri: Uri, column: String, read: (android.database.Cursor) -> T): T? = runCatching {
        context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor -> if (cursor.moveToFirst() && cursor.getColumnIndex(column) >= 0) read(cursor) else null }
    }.getOrNull()
    fun errorMessage(context: Context, error: Error, mimeType: String? = null) = when (error) {
        Error.UNKNOWN_TYPE -> context.getString(com.newoether.agora.R.string.file_unknown_type)
        Error.UNSUPPORTED_TYPE -> context.getString(com.newoether.agora.R.string.file_unsupported_type, mimeType ?: "?")
        Error.TOO_LARGE -> context.getString(com.newoether.agora.R.string.file_too_large)
    }
}
