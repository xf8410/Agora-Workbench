package com.newoether.agora.courier

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One file discovered on the phone, ready to be packed into a courier volume. */
data class PlannedFile(
    val absolutePath: String,
    val relativePath: String,
    val sizeBytes: Long,
)

/** One planned ZIP volume: boundary cuts fall between files, never inside one. */
data class VolumePlan(
    /** 1-based volume number (part_001.zip). */
    val partIndex: Int,
    val files: List<PlannedFile>,
    val totalBytes: Long,
    /** True when a single file exceeds [CourierLimits.maxVolumeBytes] and got its own volume. */
    val singleOversize: Boolean,
)

/** Boundary limits for one courier upload task (all configurable via tool arguments). */
data class CourierLimits(
    val maxVolumeBytes: Long,
    val maxTotalBytes: Long,
    val maxFiles: Int,
) {
    init {
        require(maxVolumeBytes > 0) { "max_volume_mb must be positive" }
        require(maxTotalBytes >= maxVolumeBytes) { "max_total_mb must be >= max_volume_mb" }
        require(maxFiles in 1..MAX_FILES_CEILING) { "max_files must be in 1..$MAX_FILES_CEILING" }
    }

    companion object {
        /** Hard ceiling so a single tool call can never run away unbounded. */
        const val MAX_FILES_CEILING = 20_000
    }
}

/**
 * Pure volume planner. Files are packed sequentially until the next file would overflow the
 * volume cap; a single file larger than the cap becomes its own volume (never split here —
 * callers that need byte-level splitting of one huge file use the raw-part path instead).
 */
object CourierVolumePlanner {

    /**
     * First-fit 装箱：保持输入顺序，每个文件放入第一个装得下的开放卷，放不下才开新卷，
     * 超过单卷上限的文件先冲刷开放卷再自成 singleOversize 卷。开放卷按创建顺序编号。
     */
    fun plan(files: List<PlannedFile>, limits: CourierLimits): List<VolumePlan> {
        val volumes = mutableListOf<VolumePlan>()
        val openFiles = mutableListOf<MutableList<PlannedFile>>()
        val openBytes = mutableListOf<Long>()

        fun flushOpen() {
            for (i in openFiles.indices) {
                volumes += VolumePlan(volumes.size + 1, openFiles[i].toList(), openBytes[i], false)
            }
            openFiles.clear()
            openBytes.clear()
        }

        for (file in files) {
            if (file.sizeBytes > limits.maxVolumeBytes) {
                flushOpen()
                volumes += VolumePlan(volumes.size + 1, listOf(file), file.sizeBytes, singleOversize = true)
                continue
            }
            var idx = -1
            for (i in openFiles.indices) {
                if (openBytes[i] + file.sizeBytes <= limits.maxVolumeBytes) { idx = i; break }
            }
            if (idx >= 0) {
                openFiles[idx] += file
                openBytes[idx] += file.sizeBytes
            } else {
                openFiles += mutableListOf(file)
                openBytes += file.sizeBytes
            }
        }
        flushOpen()
        return volumes
    }
}

/** One uploaded volume as reported back to the caller (and written into the manifest). */
data class UploadedVolume(
    val zipName: String,
    val githubPath: String,
    val sizeBytes: Long,
    val sha256: String,
    val files: List<String>,
    val singleOversize: Boolean = false,
)

/** One failed item, reported verbatim so the cloud side knows what to retry. */
data class CourierError(val path: String, val error: String)

/**
 * ZIP-naming convention shared with the cloud side. Both modes use part_001.zip / part_002.zip …
 * plus one manifest.json; [MODE_DIR_ZIP] volumes are standard ZIP archives of whole files,
 * [MODE_FILE_RAW_SPLIT] volumes are raw byte slices of one huge file (concatenate to restore).
 */
object CourierManifest {
    const val MODE_DIR_ZIP = "dir_zip"
    const val MODE_FILE_RAW_SPLIT = "file_raw_split"
    const val VOLUME_FORMAT_ZIP_ARCHIVE = "zip_archive"
    const val VOLUME_FORMAT_RAW_PART = "raw_part"
    const val MANIFEST_NAME = "manifest.json"
    private const val PART_NAME_PATTERN = "part_%03d.zip"

    fun partName(partIndex: Int): String = PART_NAME_PATTERN.format(partIndex)

    /**
     * Builds the manifest JSON the cloud side consumes to reassemble the payload.
     * Every field is written explicitly — no implicit assumptions on the receiving end.
     *
     * Hash semantics per mode:
     * - [MODE_DIR_ZIP]: [catalogSha256] = SHA-256 of the "relativePath\nsizeBytes\n" catalog lines
     *   (verifies the delivered file set); [originalSha256] stays null.
     * - [MODE_FILE_RAW_SPLIT]: [originalSha256] = SHA-256 of the complete source file bytes;
     *   [originalFileName]/[partBytes] tell the cloud side how to concatenate.
     */
    fun buildJson(
        mode: String,
        volumeFormat: String,
        sourcePath: String,
        targetPrefix: String,
        volumes: List<UploadedVolume>,
        truncated: Boolean,
        finishedAtEpochMs: Long,
        originalSha256: String? = null,
        catalogSha256: String? = null,
        originalFileName: String? = null,
        partBytes: Long? = null,
    ): JsonObject = buildJsonObject {
        put("mode", mode)
        put("volume_format", volumeFormat)
        put("source_path", sourcePath)
        put("target_prefix", targetPrefix)
        put("volume_count", volumes.size)
        put("uploaded_total_bytes", volumes.sumOf { it.sizeBytes })
        put("truncated", truncated)
        put("finished_at_epoch_ms", finishedAtEpochMs)
        originalSha256?.let { put("original_sha256", it) }
        catalogSha256?.let { put("catalog_sha256", it) }
        originalFileName?.let { put("original_file_name", it) }
        partBytes?.let { put("part_bytes", it) }
        put("volumes", JsonArray(volumes.map { volume ->
            buildJsonObject {
                put("zip_name", volume.zipName)
                put("github_path", volume.githubPath)
                put("size_bytes", volume.sizeBytes)
                put("sha256", volume.sha256)
                if (volumeFormat == VOLUME_FORMAT_ZIP_ARCHIVE) {
                    put("files", JsonArray(volume.files.map { JsonPrimitive(it) }))
                }
                if (volume.singleOversize) put("single_oversize", true)
            }
        }))
    }
}
