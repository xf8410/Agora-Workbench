package com.newoether.agora.courier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CourierManifestTest {
    @Test
    fun `dir_zip manifest carries the catalog hash and per-volume file lists`() {
        val manifest = CourierManifest.buildJson(
            mode = CourierManifest.MODE_DIR_ZIP,
            volumeFormat = CourierManifest.VOLUME_FORMAT_ZIP_ARCHIVE,
            sourcePath = "/storage/emulated/0/Android/data/com.example/files/UnityCache/Shared",
            targetPrefix = "inbox/20260907/",
            volumes = listOf(
                UploadedVolume("part_001.zip", "inbox/20260907/part_001.zip", 90_000_000, "sha-vol-1", listOf("a.bin", "b.bin")),
                UploadedVolume("part_002.zip", "inbox/20260907/part_002.zip", 40_000_000, "sha-vol-2", listOf("c.bin"), singleOversize = true),
            ),
            truncated = false,
            finishedAtEpochMs = 1_786_000_000_000,
            catalogSha256 = "catalog-sha",
        )

        assertEquals(CourierManifest.MODE_DIR_ZIP, manifest.getValue("mode").let { it.toString().trim('"') })
        assertEquals(CourierManifest.VOLUME_FORMAT_ZIP_ARCHIVE, manifest.getValue("volume_format").let { it.toString().trim('"') })
        assertEquals("catalog-sha", manifest.getValue("catalog_sha256").let { it.toString().trim('"') })
        assertNull(manifest["original_sha256"])
        assertFalse(manifest.getValue("truncated").let { it.toString().toBoolean() })
        val volumes = manifest.getValue("volumes").toString()
        assertTrue(volumes.contains("part_001.zip") && volumes.contains("sha-vol-2"))
        assertTrue(volumes.contains("a.bin") && volumes.contains("single_oversize"))
    }

    @Test
    fun `file_raw_split manifest carries the restore recipe`() {
        val manifest = CourierManifest.buildJson(
            mode = CourierManifest.MODE_FILE_RAW_SPLIT,
            volumeFormat = CourierManifest.VOLUME_FORMAT_RAW_PART,
            sourcePath = "/storage/emulated/0/Download/game.zip",
            targetPrefix = "courier/20260907/",
            volumes = listOf(
                UploadedVolume("part_001.zip", "courier/20260907/part_001.zip", 32_000_000, "sha-p1", emptyList()),
                UploadedVolume("part_002.zip", "courier/20260907/part_002.zip", 18_000_000, "sha-p2", emptyList()),
            ),
            truncated = false,
            finishedAtEpochMs = 1_786_000_000_000,
            originalSha256 = "content-sha",
            originalFileName = "game.zip",
            partBytes = 32_000_000,
        )

        assertEquals("content-sha", manifest.getValue("original_sha256").let { it.toString().trim('"') })
        assertNull(manifest["catalog_sha256"])
        assertEquals("game.zip", manifest.getValue("original_file_name").let { it.toString().trim('"') })
        val text = manifest.toString()
        // 云端拼接契约：mode + part_bytes + 卷序都在
        assertTrue(text.contains(CourierManifest.MODE_FILE_RAW_SPLIT))
        assertTrue(text.contains(CourierManifest.VOLUME_FORMAT_RAW_PART))
        assertTrue(text.contains("part_bytes"))
        // raw 模式不写 files 列表（切片不是完整文件）
        assertFalse(text.contains("\"files\""))
    }
}
