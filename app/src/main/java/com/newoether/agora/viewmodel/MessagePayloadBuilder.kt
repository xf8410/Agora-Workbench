package com.newoether.agora.viewmodel

import android.app.Application
import android.net.Uri
import com.newoether.agora.R
import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.util.AttachmentSourceReader
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import com.newoether.agora.util.PdfPageRenderer
import com.newoether.agora.util.SpreadsheetReader
import java.security.MessageDigest

/** Resolves outgoing attachments into image paths and durable structured metadata. */
class MessagePayloadBuilder(
    private val generationManager: GenerationManager,
    private val onSnackbar: suspend (String) -> Unit,
) {
    data class MessagePayload(val allImages: List<String>, val attachmentMeta: AttachmentMeta?)

    suspend fun buildMessagePayload(app: Application, images: List<String>, attachments: List<SelectedAttachment>): MessagePayload {
        val mediaUris = mutableListOf<String>()
        val directPaths = mutableListOf<String>()
        val sliceConfigs = mutableMapOf<String, VideoSliceConfig>()
        val metaItems = mutableListOf<AttachmentItem>()
        var nextImageIndex = 0
        mediaUris.addAll(images)

        for (att in attachments) {
            when (att.type) {
                "image" -> {
                    mediaUris += att.localPath ?: att.uri
                    metaItems += AttachmentItem(originalUri = att.uri, type = "image", mimeType = att.mimeType, imageIndex = nextImageIndex)
                    nextImageIndex++
                }
                "video" -> {
                    val ext = when { att.mimeType?.contains("webm") == true -> "webm"; att.mimeType?.contains("quicktime") == true -> "mov"; else -> "mp4" }
                    val file = java.io.File(app.filesDir, "vid_original_${java.util.UUID.randomUUID()}.$ext")
                    val localUri = try {
                        app.contentResolver.openInputStream(Uri.parse(att.uri))?.use { input -> file.outputStream().use { input.copyTo(it) } }
                        "file://${file.absolutePath}"
                    } catch (_: Exception) { att.uri }
                    if (!att.processedFrames.isNullOrEmpty()) {
                        metaItems += AttachmentItem(localUri, "video", att.fileName, att.mimeType, nextImageIndex, att.frameCount)
                        directPaths.addAll(att.processedFrames)
                        nextImageIndex += att.processedFrames.size
                    } else {
                        val count = att.frameCount ?: 1
                        metaItems += AttachmentItem(localUri, "video", att.fileName, att.mimeType, nextImageIndex, att.frameCount)
                        mediaUris += att.uri
                        if (att.frameCount != null && att.frameCount > 1 && att.sliceIntervalMs != null) {
                            sliceConfigs[att.uri] = VideoSliceConfig(att.sliceIntervalMs * 1000L, att.frameCount)
                        }
                        nextImageIndex += count
                    }
                }
                "file" -> {
                    val source = att.localPath ?: att.uri
                    if (SpreadsheetReader.isSpreadsheet(att.fileName, att.mimeType)) {
                        val parsed = SpreadsheetReader.read(app, source, att.fileName, att.mimeType)
                        if (parsed == null) {
                            metaItems += AttachmentItem(
                                originalUri = att.localPath?.let { "file://$it" } ?: att.uri,
                                type = "file", fileName = att.fileName, mimeType = att.mimeType,
                                warning = "Spreadsheet parsing failed",
                            )
                        } else {
                            val digest = MessageDigest.getInstance("SHA-256").digest(parsed.toByteArray())
                                .joinToString("") { "%02x".format(it) }
                            val directory = java.io.File(app.filesDir, "spreadsheet_parsed").apply { mkdirs() }
                            val sidecar = java.io.File(directory, "$digest.tsv")
                            if (!sidecar.exists()) sidecar.writeText(parsed)
                            metaItems += AttachmentItem(
                                originalUri = "file://${sidecar.absolutePath}",
                                type = "file", fileName = att.fileName, mimeType = att.mimeType,
                                contentPath = sidecar.absolutePath,
                                sourceUri = att.localPath?.let { "file://$it" } ?: att.uri,
                            )
                        }
                    } else {
                        val text = AttachmentSourceReader.readText(app, source, Constants.MAX_FILE_CONTENT_READ_LENGTH)
                        if (text == null) DebugLog.e("MessagePayloadBuilder", "Failed to read attachment: ${att.fileName}")
                        metaItems += AttachmentItem(
                            originalUri = att.localPath?.let { "file://$it" } ?: att.uri,
                            type = "file", fileName = att.fileName, mimeType = att.mimeType, textContent = text,
                        )
                    }
                }
                "pdf" -> {
                    val paths = if (!att.preRenderedPaths.isNullOrEmpty()) {
                        val selected = att.selectedPages ?: att.preRenderedPaths.indices.toSet()
                        att.preRenderedPaths.filterIndexed { index, _ -> index in selected }
                    } else PdfPageRenderer.renderAsImages(app, Uri.parse(att.uri), att.selectedPages)
                    if (paths.isEmpty()) { onSnackbar(app.getString(R.string.pdf_render_failed)); continue }
                    metaItems += AttachmentItem(att.uri, "pdf", att.fileName, "application/pdf", nextImageIndex, paths.size)
                    directPaths.addAll(paths)
                    nextImageIndex += paths.size
                }
            }
        }

        val processed = if (mediaUris.isNotEmpty()) generationManager.processImages(mediaUris, sliceConfigs) else emptyList()
        val allImages = processed + directPaths
        val ranges = mutableListOf<IntRange>()
        var pos = 0
        for (uri in mediaUris) {
            val end = minOf(pos + (sliceConfigs[uri]?.frameCount ?: 1), processed.size)
            ranges += pos until end
            pos = end
        }
        val adjusted = metaItems.map { item ->
            val index = item.imageIndex
            when {
                index == null -> item
                index < mediaUris.size && index < ranges.size -> item.copy(imageIndex = ranges[index].first)
                index in mediaUris.size until mediaUris.size + directPaths.size -> item.copy(imageIndex = processed.size + index - mediaUris.size)
                else -> item
            }
        }
        return MessagePayload(allImages, adjusted.takeIf { it.isNotEmpty() }?.let(::AttachmentMeta))
    }
}
