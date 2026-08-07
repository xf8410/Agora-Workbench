package com.newoether.agora.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

const val SPREADSHEET_SIDECAR_PREFIX = "@agora-spreadsheet-sidecar:"

@Serializable
data class AttachmentMeta(val items: List<AttachmentItem> = emptyList())

/** Workbook contents stay outside Room; consumers receive a stable sidecar reference. */
@Serializable(with = AttachmentItemSerializer::class)
class AttachmentItem(
    val originalUri: String? = null, val type: String,
    val fileName: String? = null, val mimeType: String? = null,
    val imageIndex: Int? = null, val pageCount: Int? = null,
    val warning: String? = null, textContent: String? = null,
    val contentPath: String? = null, val transcription: String? = null,
) {
    internal val inlineTextContent = if (contentPath == null) textContent else null
    val textContent: String? get() = inlineTextContent ?: contentPath?.let { SPREADSHEET_SIDECAR_PREFIX + it }
    fun copy(originalUri: String? = this.originalUri, type: String = this.type, fileName: String? = this.fileName, mimeType: String? = this.mimeType, imageIndex: Int? = this.imageIndex, pageCount: Int? = this.pageCount, warning: String? = this.warning, textContent: String? = this.inlineTextContent, contentPath: String? = this.contentPath, transcription: String? = this.transcription) = AttachmentItem(originalUri, type, fileName, mimeType, imageIndex, pageCount, warning, if (contentPath == null) textContent else null, contentPath, transcription)
}

@Serializable
private data class AttachmentItemSurrogate(
    val originalUri: String? = null, val type: String,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    @SerialName("image_index") val imageIndex: Int? = null,
    @SerialName("page_count") val pageCount: Int? = null,
    val warning: String? = null,
    @SerialName("text_content") val textContent: String? = null,
    @SerialName("content_path") val contentPath: String? = null,
    val transcription: String? = null,
)
object AttachmentItemSerializer : KSerializer<AttachmentItem> {
    private val delegate = AttachmentItemSurrogate.serializer(); override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: AttachmentItem) = delegate.serialize(encoder, AttachmentItemSurrogate(value.originalUri, value.type, value.fileName, value.mimeType, value.imageIndex, value.pageCount, value.warning, value.inlineTextContent, value.contentPath, value.transcription))
    override fun deserialize(decoder: Decoder): AttachmentItem { val v = delegate.deserialize(decoder); return AttachmentItem(v.originalUri, v.type, v.fileName, v.mimeType, v.imageIndex, v.pageCount, v.warning, v.textContent, v.contentPath, v.transcription) }
}

@Serializable
data class SelectedAttachment(val uri: String, val type: String, val frameCount: Int? = null, val sliceIntervalMs: Long? = null, val fileName: String? = null, val mimeType: String? = null, val fileSize: Long? = null, val processedFrames: List<String>? = null, val selectedPages: Set<Int>? = null, val preRenderedPaths: List<String>? = null, val localPath: String? = null)
