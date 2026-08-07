package com.newoether.agora.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class AttachmentMeta(val items: List<AttachmentItem> = emptyList())

@Serializable
data class AttachmentItem(
    /** URI used by the existing preview chain. For spreadsheets this points to parsed TSV. */
    val originalUri: String? = null,
    val type: String,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    @SerialName("image_index") val imageIndex: Int? = null,
    @SerialName("page_count") val pageCount: Int? = null,
    val warning: String? = null,
    @SerialName("text_content") val textContent: String? = null,
    /** Full parsed workbook outside the Room message row. */
    @SerialName("content_path") val contentPath: String? = null,
    /** App-private copy of the original workbook bytes. */
    @SerialName("source_uri") val sourceUri: String? = null,
    @SerialName("transcription") val transcription: String? = null
)

@Serializable
data class SelectedAttachment(
    val uri: String,
    val type: String,
    val frameCount: Int? = null,
    val sliceIntervalMs: Long? = null,
    val fileName: String? = null,
    val mimeType: String? = null,
    val fileSize: Long? = null,
    val processedFrames: List<String>? = null,
    val selectedPages: Set<Int>? = null,
    val preRenderedPaths: List<String>? = null,
    val localPath: String? = null
)
