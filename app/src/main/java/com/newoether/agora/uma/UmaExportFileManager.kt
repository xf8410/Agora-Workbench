package com.newoether.agora.uma

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class UmaExportedZip(
    val file: File,
    val sessionId: String,
    val byteLength: Long,
    val modifiedAtMs: Long,
)

data class UmaSavedExport(
    val displayName: String,
    val byteLength: Long,
    val sha256: String,
    val destination: String,
)

/** Lists private completed exports and copies them into the user-visible Downloads collection. */
class UmaExportFileManager(private val context: Context) {
    private val exportDirectory = File(context.cacheDir, "agora-uma/exports")

    fun listCompleted(): List<UmaExportedZip> =
        exportDirectory.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.endsWith(".zip", ignoreCase = true) }
            ?.filterNot { it.name.endsWith(".zip.part", ignoreCase = true) }
            ?.map { file ->
                UmaExportedZip(
                    file = file,
                    sessionId = file.name.removeSuffix(".zip"),
                    byteLength = file.length(),
                    modifiedAtMs = file.lastModified(),
                )
            }
            ?.sortedByDescending { it.modifiedAtMs }
            ?.toList()
            ?: emptyList()

    suspend fun saveToDownloads(export: UmaExportedZip): UmaSavedExport =
        withContext(Dispatchers.IO) {
            require(export.file.isFile) { "ZIP 文件不存在" }
            require(export.file.length() > 0) { "ZIP 文件为空" }
            val displayName = safeDisplayName(export.file.name)
            val digest = MessageDigest.getInstance("SHA-256")
            val destination = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveWithMediaStore(export.file, displayName, digest)
            } else {
                saveToLegacyDownloads(export.file, displayName, digest)
            }
            UmaSavedExport(
                displayName = displayName,
                byteLength = export.file.length(),
                sha256 = digest.digest().joinToString("") { "%02x".format(it) },
                destination = destination,
            )
        }

    private fun saveWithMediaStore(
        source: File,
        displayName: String,
        digest: MessageDigest,
    ): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AgoraUma")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = requireNotNull(
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ) { "无法在系统下载目录创建文件" }
        try {
            resolver.openOutputStream(uri, "w").use { output ->
                requireNotNull(output) { "无法打开系统下载文件" }
                FileInputStream(source).use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                    output.flush()
                }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return "Download/AgoraUma/$displayName"
        } catch (failure: Throwable) {
            resolver.delete(uri, null, null)
            throw failure
        }
    }

    @Suppress("DEPRECATION")
    private fun saveToLegacyDownloads(
        source: File,
        displayName: String,
        digest: MessageDigest,
    ): String {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "AgoraUma",
        )
        require(directory.mkdirs() || directory.isDirectory) { "无法创建 Download/AgoraUma" }
        val target = uniqueTarget(directory, displayName)
        val temporary = File(directory, target.name + ".part")
        if (temporary.exists()) require(temporary.delete()) { "无法替换临时文件" }
        FileInputStream(source).use { input ->
            FileOutputStream(temporary, false).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                }
                output.fd.sync()
            }
        }
        require(temporary.renameTo(target)) { "无法完成下载文件" }
        return target.absolutePath
    }

    private fun uniqueTarget(directory: File, displayName: String): File {
        val direct = File(directory, displayName)
        if (!direct.exists()) return direct
        val stem = displayName.removeSuffix(".zip")
        var index = 1
        while (true) {
            val candidate = File(directory, "$stem-$index.zip")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun safeDisplayName(value: String): String {
        require(value.isNotBlank() && value.endsWith(".zip", ignoreCase = true)) {
            "无效 ZIP 文件名"
        }
        require('/' !in value && '\\' !in value && value != "." && value != "..") {
            "不安全的 ZIP 文件名"
        }
        return value
    }
}
