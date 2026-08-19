package com.newoether.agora.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/** Attachment metadata helpers. File selection itself is unrestricted. */
object FileValidator {
    enum class Error { UNKNOWN_TYPE, UNSUPPORTED_TYPE, TOO_LARGE }
    data class Result(val valid: Boolean, val error: Error? = null, val mimeType: String? = null)

    /** Accept every selected readable document, including ZIP archives and unknown MIME types. */
    fun validate(context: Context, uri: Uri): Result {
        val mimeType = try { context.contentResolver.getType(uri) } catch (_: Exception) { null }
        return Result(true, mimeType = mimeType)
    }

    fun resolveMimeType(context: Context, uriString: String): String? =
        try { context.contentResolver.getType(Uri.parse(uriString)) } catch (_: Exception) { null }

    fun resolveFileName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (!c.moveToFirst()) null else c.getString(c.getColumnIndex(OpenableColumns.DISPLAY_NAME).coerceAtLeast(0))
        }
    } catch (_: Exception) { null }

    fun resolveFileSize(context: Context, uri: Uri): Long? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) null else c.getLong(c.getColumnIndex(OpenableColumns.SIZE).coerceAtLeast(0))
        }
    } catch (_: Exception) { null }

    fun errorMessage(context: Context, error: Error, mimeType: String? = null): String = when (error) {
        Error.UNKNOWN_TYPE -> context.getString(com.newoether.agora.R.string.file_unknown_type)
        Error.UNSUPPORTED_TYPE -> context.getString(com.newoether.agora.R.string.file_unsupported_type, mimeType ?: "?")
        Error.TOO_LARGE -> context.getString(com.newoether.agora.R.string.file_too_large)
    }
}
