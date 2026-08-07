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

class MessagePayloadBuilder(private val generationManager: GenerationManager, private val onSnackbar: suspend (String) -> Unit) {
    data class MessagePayload(val allImages: List<String>, val attachmentMeta: AttachmentMeta?)
    suspend fun buildMessagePayload(app: Application, images: List<String>, attachments: List<SelectedAttachment>): MessagePayload {
        val media = images.toMutableList(); val direct = mutableListOf<String>(); val slices = mutableMapOf<String, VideoSliceConfig>(); val meta = mutableListOf<AttachmentItem>(); var nextImage = 0
        for (att in attachments) when (att.type) {
            "image" -> { media += att.localPath ?: att.uri; meta += AttachmentItem(att.uri, "image", mimeType = att.mimeType, imageIndex = nextImage); nextImage++ }
            "video" -> {
                val ext = if (att.mimeType?.contains("webm") == true) "webm" else if (att.mimeType?.contains("quicktime") == true) "mov" else "mp4"
                val original = java.io.File(app.filesDir, "vid_original_${java.util.UUID.randomUUID()}.$ext")
                val local = try { app.contentResolver.openInputStream(Uri.parse(att.uri))?.use { input -> original.outputStream().use { input.copyTo(it) } }; "file://${original.absolutePath}" } catch (_: Exception) { att.uri }
                if (!att.processedFrames.isNullOrEmpty()) { meta += AttachmentItem(local, "video", att.fileName, att.mimeType, nextImage, att.frameCount); direct.addAll(att.processedFrames); nextImage += att.processedFrames.size }
                else { val count = att.frameCount ?: 1; meta += AttachmentItem(local, "video", att.fileName, att.mimeType, nextImage, att.frameCount); media += att.uri; if (att.frameCount != null && att.frameCount > 1 && att.sliceIntervalMs != null) slices[att.uri] = VideoSliceConfig(att.sliceIntervalMs * 1000L, att.frameCount); nextImage += count }
            }
            "file" -> {
                val source = att.localPath ?: att.uri
                if (SpreadsheetReader.isSpreadsheet(att.fileName, att.mimeType)) {
                    val parsed = SpreadsheetReader.read(app, source, att.fileName, att.mimeType)
                    if (parsed == null) meta += AttachmentItem(att.localPath?.let { "file://$it" } ?: att.uri, "file", att.fileName, att.mimeType, warning = "Spreadsheet parsing failed")
                    else {
                        val hash = MessageDigest.getInstance("SHA-256").digest(parsed.toByteArray()).joinToString("") { "%02x".format(it) }
                        val dir = java.io.File(app.filesDir, "spreadsheet_parsed").apply { mkdirs() }; val sidecar = java.io.File(dir, "$hash.tsv"); if (!sidecar.exists()) sidecar.writeText(parsed)
                        meta += AttachmentItem(att.localPath?.let { "file://$it" } ?: att.uri, "file", att.fileName, att.mimeType, contentPath = sidecar.absolutePath)
                    }
                } else {
                    val text = AttachmentSourceReader.readText(app, source, Constants.MAX_FILE_CONTENT_READ_LENGTH); if (text == null) DebugLog.e("MessagePayloadBuilder", "Failed to read attachment: ${att.fileName}")
                    meta += AttachmentItem(att.localPath?.let { "file://$it" } ?: att.uri, "file", att.fileName, att.mimeType, textContent = text)
                }
            }
            "pdf" -> {
                val pages = if (!att.preRenderedPaths.isNullOrEmpty()) { val selected = att.selectedPages ?: att.preRenderedPaths.indices.toSet(); att.preRenderedPaths.filterIndexed { i, _ -> i in selected } } else PdfPageRenderer.renderAsImages(app, Uri.parse(att.uri), att.selectedPages)
                if (pages.isEmpty()) { onSnackbar(app.getString(R.string.pdf_render_failed)); continue }
                meta += AttachmentItem(att.uri, "pdf", att.fileName, "application/pdf", nextImage, pages.size); direct.addAll(pages); nextImage += pages.size
            }
        }
        val processed = if (media.isNotEmpty()) generationManager.processImages(media, slices) else emptyList(); val all = processed + direct; val ranges = mutableListOf<IntRange>(); var pos = 0
        for (uri in media) { val end = minOf(pos + (slices[uri]?.frameCount ?: 1), processed.size); ranges += pos until end; pos = end }
        val adjusted = meta.map { item -> val i = item.imageIndex; when { i == null -> item; i < media.size && i < ranges.size -> item.copy(imageIndex = ranges[i].first); i in media.size until media.size + direct.size -> item.copy(imageIndex = processed.size + i - media.size); else -> item } }
        return MessagePayload(all, adjusted.takeIf { it.isNotEmpty() }?.let(::AttachmentMeta))
    }
}
