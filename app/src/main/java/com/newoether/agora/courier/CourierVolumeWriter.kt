package com.newoether.agora.courier

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes courier volumes to temporary files. ZIP volumes contain whole files (entry name =
 * path relative to the delivery root); raw parts are plain byte slices of one oversized file
 * and are reassembled by concatenation on the cloud side (see CourierManifest.mode).
 */
object CourierVolumeWriter {

    /** Writes one ZIP volume atomically (`.part` temp then rename). */
    fun writeZipVolume(
        targetFile: File,
        files: List<PlannedFile>,
        open: (String) -> InputStream,
    ) {
        require(files.isNotEmpty()) { "ZIP volume must contain at least one file" }
        val parent = targetFile.parentFile
        require(parent != null && (parent.isDirectory || parent.mkdirs())) {
            "cannot create courier temp directory"
        }
        val temporary = File(parent, targetFile.name + ".part")
        try {
            ZipOutputStream(FileOutputStream(temporary, false).buffered()).use { zip ->
                files.forEach { planned ->
                    zip.putNextEntry(ZipEntry(planned.relativePath))
                    open(planned.absolutePath).use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            if (targetFile.exists()) require(targetFile.delete()) { "cannot replace existing volume" }
            require(temporary.renameTo(targetFile)) { "cannot finalize volume ${targetFile.name}" }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    /** Writes one raw byte slice ([offset], [length]) of an oversized single file. */
    fun writeRawPart(
        targetFile: File,
        open: () -> InputStream,
        offset: Long,
        length: Long,
    ) {
        require(offset >= 0 && length > 0) { "invalid raw part range" }
        val parent = targetFile.parentFile
        require(parent != null && (parent.isDirectory || parent.mkdirs())) {
            "cannot create courier temp directory"
        }
        val temporary = File(parent, targetFile.name + ".part")
        try {
            var remaining = length
            open().use { input ->
                require(input.skip(offset) == offset) { "cannot seek to raw part offset" }
                FileOutputStream(temporary, false).buffered().use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (remaining > 0) {
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        require(read >= 0) { "source ended before the raw part was complete" }
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }
            require(remaining == 0L) { "raw part incomplete" }
            if (targetFile.exists()) require(targetFile.delete()) { "cannot replace existing volume" }
            require(temporary.renameTo(targetFile)) { "cannot finalize volume ${targetFile.name}" }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private const val BUFFER_BYTES = 256 * 1024
}
