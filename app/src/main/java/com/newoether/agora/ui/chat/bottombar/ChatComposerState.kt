package com.newoether.agora.ui.chat.bottombar

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.ui.chat.VideoSliceDialog
import com.newoether.agora.util.DebugLog
import com.newoether.agora.ui.common.AgoraHaptics
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.util.FileValidator
import com.newoether.agora.util.PdfPageRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class ChatComposerState(
    private val context: Context,
    private val haptics: AgoraHaptics,
    private val scope: CoroutineScope,
) {
    var selectedAttachments by mutableStateOf<List<SelectedAttachment>>(emptyList())
    var processingStates by mutableStateOf<Map<String, Float>>(emptyMap())
    var pendingSend by mutableStateOf(false)
    var showPdfPageDialog by mutableStateOf(false)
    var pendingPdfUri by mutableStateOf<String?>(null)
    var pendingPdfPages by mutableIntStateOf(0)
    var pendingPdfFileName by mutableStateOf<String?>(null)
    var pendingPdfMimeType by mutableStateOf<String?>(null)
    var pendingPdfRenderedPaths by mutableStateOf<List<String>>(emptyList())
    var pendingPdfIsRendering by mutableStateOf(false)
    var pendingPdfRenderProgress by mutableStateOf(0 to 0)
    var pdfDialogHiddenForPreview by mutableStateOf(false)
    var pdfRenderJob by mutableStateOf<Job?>(null)
    val videoExtractionJobs = mutableMapOf<String, Job>()
    var showVideoSliceDialog by mutableStateOf(false)
    var pendingVideoUri by mutableStateOf<String?>(null)
    var pendingVideoDurationMs by mutableLongStateOf(0L)
    var pendingVideoQueue by mutableStateOf<List<String>>(emptyList())
    var rejectedMessage by mutableStateOf<String?>(null)

    fun clearAttachments() { selectedAttachments = emptyList() }
    private suspend fun copyToPrivate(uri: Uri, ext: String): String? = withContext(Dispatchers.IO) {
        val target = java.io.File(context.filesDir, "att_${UUID.randomUUID()}.$ext")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext null
            target.absolutePath
        } catch (_: Exception) { runCatching { target.delete() }; null }
    }
    fun removeAttachmentAt(index: Int) {
        haptics.selection()
        val removed = selectedAttachments.getOrNull(index)
        removed?.let { com.newoether.agora.util.AttachmentFiles.deleteBacking(it) }
        if (removed != null) videoExtractionJobs.remove(removed.uri)?.cancel()
        selectedAttachments = selectedAttachments.toMutableList().also { it.removeAt(index) }
        removed?.uri?.let { processingStates = processingStates - it }
    }
    fun processNextVideo() {
        if (pendingVideoQueue.isNotEmpty()) {
            val uri = pendingVideoQueue.first(); pendingVideoQueue = pendingVideoQueue.drop(1)
            val durationMs = try { android.media.MediaMetadataRetriever().run {
                setDataSource(context, android.net.Uri.parse(uri))
                extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } } catch (_: Exception) { 0L }
            pendingVideoUri = uri; pendingVideoDurationMs = durationMs; showVideoSliceDialog = true
        }
    }
    suspend fun extractVideoFrames(videoUri: String, frameCount: Int, intervalMs: Long): List<String> =
        withContext(Dispatchers.IO) { emptyList() }
    fun onPickImages(uris: List<Uri>) {
        if (uris.isNotEmpty()) haptics.selection()
        uris.forEach { uri ->
            val uriStr = uri.toString()
            val att = SelectedAttachment(uriStr, "image", mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull())
            selectedAttachments += att
            processingStates = processingStates + (uriStr to 0f)
            scope.launch(Dispatchers.IO) {
                val path = copyToPrivate(uri, "img")
                if (path != null) selectedAttachments = selectedAttachments.map { if (it.uri == uriStr) it.copy(localPath = path) else it }
                else rejectedMessage = "Failed to copy image to local storage"
                processingStates = processingStates - uriStr
            }
        }
    }
    fun onPickVideos(uris: List<Uri>) { pendingVideoQueue = pendingVideoQueue + uris.map(Uri::toString); if (!showVideoSliceDialog) processNextVideo() }
    fun onPickFiles(uris: List<Uri>, onInitPdfSelection: ((Set<Int>) -> Unit)?) {
        val accepted = mutableListOf<SelectedAttachment>()
        for (uri in uris) {
            val validation = FileValidator.validate(context, uri)
            val name = FileValidator.resolveFileName(context, uri)
            val mime = validation.mimeType
            val isZip = mime.equals("application/zip", true) ||
                mime.equals("application/x-zip-compressed", true) || name?.endsWith(".zip", true) == true
            val type = when {
                mime.equals("application/pdf", true) -> "pdf"
                isZip -> "zip"
                else -> "file"
            }
            accepted += SelectedAttachment(uri.toString(), type, fileName = name, mimeType = mime,
                fileSize = FileValidator.resolveFileSize(context, uri))
        }
        if (accepted.isNotEmpty()) haptics.selection()
        selectedAttachments += accepted
        accepted.filter { it.type == "file" || it.type == "zip" }.forEach { att ->
            val ext = if (att.type == "zip") "zip" else att.fileName?.substringAfterLast('.', "bin") ?: "bin"
            processingStates = processingStates + (att.uri to 0f)
            scope.launch(Dispatchers.IO) {
                val path = copyToPrivate(Uri.parse(att.uri), ext)
                if (path != null) selectedAttachments = selectedAttachments.map { if (it.uri == att.uri) it.copy(localPath = path) else it }
                else rejectedMessage = "Failed to copy file to local storage"
                processingStates = processingStates - att.uri
            }
        }
    }
    fun addSlicedVideo(vidUri: String, frameCount: Int, intervalMs: Long) {
        selectedAttachments += SelectedAttachment(vidUri, "video", frameCount = frameCount, sliceIntervalMs = intervalMs, mimeType = "video/*")
    }
}

@Composable
fun rememberChatComposerState(): ChatComposerState {
    val context = LocalContext.current; val haptics = LocalAgoraHaptics.current; val scope = rememberCoroutineScope()
    return remember(context, haptics, scope) { ChatComposerState(context, haptics, scope) }
}
