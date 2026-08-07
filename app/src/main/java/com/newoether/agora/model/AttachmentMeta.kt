package com.newoether.agora.model

import java.io.File
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
data class AttachmentMeta(val items: List<AttachmentItem> = emptyList())

/**
 * Attachment metadata keeps large parsed workbooks outside the Room message row. The public
 * [textContent] property resolves [contentPath] only when the generation or viewer actually asks
 * for the content; serialization writes only the original inline value and the short sidecar path.
 */
@Serializable(with = AttachmentItemSerializer::class)
class AttachmentItem(
    val originalUri: String? = null,
    val type: String,
    val fileName: String? = null,
    val mimeType: String? = null,
    val imageIndex: Int? = null,
    val pageCount: Int? = null,
    val warning: String? = null,
    textContent: String? = null,
    val contentPath: String? = null,
    val sourceUri: String? = null,
    val transcription: String? = null,
) {
    internal val inlineTextContent: String? = textContent
    val textContent: String?
        get() = inlineTextContent ?: contentPath?.let { path -> runCatching { File(path).readText() }.getOrNull() }

    fun copy(
        originalUri: String? = this.originalUri,
        type: String = this.type,
        fileName: String? = this.fileName,
        mimeType: String? = this.mimeType,
        imageIndex: Int? = this.imageIndex,
        pageCount: Int? = this.pageCount,
        warning: String? = this.warning,
        textContent: String? = this.inlineTextContent,
        contentPath: String? = this.contentPath,
        sourceUri: String? = this.sourceUri,
        transcription: String? = this.transcription,
    ) = AttachmentItem(
        originalUri, type, fileName, mimeType, imageIndex, pageCount, warning,
        textContent, contentPath, sourceUri, transcription,
    )
}

@Serializable
private data class AttachmentItemSurrogate(
    val originalUri: String? = null,
    val type: String,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    @SerialName("image_index") val imageIndex: Int? = null,
    @SerialName("page_count") val pageCount: Int? = null,
    val warning: String? = null,
    @SerialName("text_content") val textContent: String? = null,
    @SerialName("content_path") val contentPath: String? = null,
    @SerialName("source_uri") val sourceUri: String? = null,
    val transcription: String? = null,
)

object AttachmentItemSerializer : KSerializer<AttachmentItem> {
    private val delegate = AttachmentItemSurrogate.serializer()
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: AttachmentItem) {
        delegate.serialize(encoder, AttachmentItemSurrogate(
            originalUri = value.originalUri,
            type = value.type,
            fileName = value.fileName,
            mimeType = value.mimeType,
            imageIndex = value.imageIndex,
            pageCount = value.pageCount,
            warning = value.warning,
            textContent = value.inlineTextContent,
            contentPath = value.contentPath,
            sourceUri = value.sourceUri,
            transcription = value.transcription,
        ))
    }

    override fun deserialize(decoder: Decoder): AttachmentItem {
        val value = delegate.deserialize(decoder)
        return AttachmentItem(
            value.originalUri, value.type, value.fileName, value.mimeType, value.imageIndex,
            value.pageCount, value.warning, value.textContent, value.contentPath,
            value.sourceUri, value.transcription,
        )
    }
}

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
    val localPath: String? = null,
)
