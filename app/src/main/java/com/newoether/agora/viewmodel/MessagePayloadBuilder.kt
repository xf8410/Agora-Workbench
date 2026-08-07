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

    suspend fun buildMessagePayload(
        app: Application,
        images: List<String>,
        attachments: List<SelectedAttachment>,
    ): MessagePayload {
        val mediaUris = mutableListOf<String>()
        val directPaths = mutableListOf<String>()
        val sliceConfigs = mutableMapOf<String, VideoSliceConfig>()
        val metaItems = mutableListOf<AttachmentItem>()
        var nextImageIndex = 0
        mediaUris.addAll(images)

        for (att in attachments) {
            when (att.type) {
                "image" -> {
                    mediaUris.add(att.localPath ?: att.uri)
                    metaItems += AttachmentItem(
                        originalUri = att.uri, type = "image", mimeType = att.mimeType,
                        imageIndex = nextImageIndex,
                    )
                    nextImageIndex++
                }
                "video" -> {
                    val videoExt = when {
                        att.mimeType?.contains("webm") == true -> "webm"
                        att.mimeType?.contains("quicktime") == true -> "mov"
                        else -> "mp4"
                    }
                    val videoFile = java.io.File(app.filesDir, "vid_original_${java.util.UUID.randomUUID()}.$videoExt")
                    val localVideoUri = try {
                        app.contentResolver.openInputStream(Uri.parse(att.uri))?.use { input ->
                            videoFile.outputStream().use { input.copyTo(it) }
                        }
                        "file://${videoFile.absolutePath}"
                    } catch (_: Exception) { att.uri }
                    if (!att.processedFrames.isNullOrEmpty()) {
                        metaItems += AttachmentItem(
                            originalUri = localVideoUri, type = "video", fileName = att.fileName,
                            mimeType = att.mimeType, imageIndex = nextImageIndex, pageCount = att.frameCount,
                        )
                        directPaths.addAll(att.processedFrames)
                        nextImageIndex += att.processedFrames.size
                    } else {
                        val frameCount = att.frameCount ?: 1
                        metaItems += AttachmentItem(
                            originalUri = localVideoUri, type = "video", fileName = att.fileName,
                            mimeType = att.mimeType, imageIndex = nextImageIndex, pageCount = att.frameCount,
                        )
                        mediaUris += att.uri
                        if (att.frameCount != null && att.frameCount > 1 && att.sliceIntervalMs != null) {
                            sliceConfigs[att.uri] = VideoSliceConfig(att.sliceIntervalMs * 1000L, att.frameCount)
                        }
                        nextImageIndex += frameCount
                    }
                }
                "file" -> {
                    val source = att.localPath ?: att.uri
                    if (SpreadsheetReader.isSpreadsheet(att.fileName, att.mimeType)) {
                        val parsed = SpreadsheetReader.read(app, source, att.fileName, att.mimeType)
                        if (parsed == null) {
                            DebugLog.e("MessagePayloadBuilder", "Failed to parse spreadsheet: ${att.fileName}")
                            metaItems += AttachmentItem(
                                originalUri = att.localPath?.let { "file://$it" } ?: att.uri,
                                type = "spreadsheet", fileName = att.fileName, mimeType = att.mimeType,
                                warning = "Spreadsheet parsing failed",
                            )
                        } else {
                            val digest = MessageDigest.getInstance("SHA-256")
                                .digest(parsed.toByteArray()).joinToString("") { "%02x".format(it) }
                            val directory = java.io.File(app.filesDir, "spreadsheet_parsed").apply { mkdirs() }
                            val sidecar = java.io.File(directory, "$digest.tsv")
                            if (!sidecar.exists()) sidecar.writeText(parsed)
                            metaItems += AttachmentItem(
                                originalUri = att.localPath?.let { "file://$it" } ?: att.uri,
                                type = "spreadsheet", fileName = att.fileName, mimeType = att.mimeType,
                                contentPath = sidecar.absolutePath,
                            )
                        }
                    } else {
                        val textContent = AttachmentSourceReader.readText(app, source, Constants.MAX_FILE_CONTENT_READ_LENGTH)
                        if (textContent == null) DebugLog.e("MessagePayloadBuilder", "Failed to read attachment: ${att.fileName}")
                        metaItems += AttachmentItem(
                            originalUri = att.localPath?.let { "file://$it" } ?: att.uri,
                            type = "file", fileName = att.fileName, mimeType = att.mimeType,
                            textContent = textContent,
                        )
                    }
                }
                "pdf" -> {
                    val pagePaths = if (!att.preRenderedPaths.isNullOrEmpty()) {
                        val selected = att.selectedPages ?: att.preRenderedPaths.indices.toSet()
                        att.preRenderedPaths.filterIndexed { index, _ -> index in selected }
                    } else PdfPageRenderer.renderAsImages(app, Uri.parse(att.uri), att.selectedPages)
                    if (pagePaths.isEmpty()) {
                        onSnackbar(app.getString(R.string.pdf_render_failed))
                        continue
                    }
                    metaItems += AttachmentItem(
                        originalUri = att.uri, type = "pdf", fileName = att.fileName,
                        mimeType = "application/pdf", imageIndex = nextImageIndex,
                        pageCount = pagePaths.size,
                    )
                    directPaths.addAll(pagePaths)
                    nextImageIndex += pagePaths.size
                }
            }
        }

        val processedImages = if (mediaUris.isNotEmpty()) generationManager.processImages(mediaUris, sliceConfigs) else emptyList()
        val allImages = processedImages + directPaths
        val uriToResultMap = mutableListOf<IntRange>()
        var position = 0
        for (uri in mediaUris) {
            val expected = sliceConfigs[uri]?.frameCount ?: 1
            val end = minOf(position + expected, processedImages.size)
            uriToResultMap += position until end
            position = end
        }
        val adjusted = metaItems.map { item ->
            val index = item.imageIndex
            when {
                index == null -> item
                index < mediaUris.size && index < uriToResultMap.size -> item.copy(imageIndex = uriToResultMap[index].first)
                index in mediaUris.size until mediaUris.size + directPaths.size ->
                    item.copy(imageIndex = processedImages.size + index - mediaUris.size)
                else -> item
            }
        }
        return MessagePayload(allImages, adjusted.takeIf { it.isNotEmpty() }?.let(::AttachmentMeta))
    }
}
