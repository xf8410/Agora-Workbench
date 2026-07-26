package com.newoether.agora.ui.chat.bottombar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.ui.common.LocalAgoraHaptics

/**
 * The composer's send / stop / pending-send FAB. Owns the "wait for attachment
 * processing then auto-send" handshake and the icon cross-fade between the three
 * states. Extracted from [ChatBottomBar].
 */
@Composable
internal fun ComposerSendButton(
    textFieldState: TextFieldState,
    composer: ChatComposerState,
    isLoading: Boolean,
    isSwitching: Boolean,
    isModelValid: Boolean,
    onSendMessage: (String, List<SelectedAttachment>) -> Boolean,
    onStopGeneration: () -> Unit,
    onCollapse: () -> Unit,
) {
    val haptics = LocalAgoraHaptics.current
    // Pending send: wait for processing to finish, then auto-send
    val anyProcessing = composer.processingStates.isNotEmpty()
    LaunchedEffect(composer.pendingSend, anyProcessing) {
        if (composer.pendingSend && !anyProcessing) {
            if (onSendMessage(textFieldState.text.toString(), composer.selectedAttachments)) {
                composer.clearAttachments()
                textFieldState.edit { replace(0, length, "") }
                onCollapse()
            }
            composer.pendingSend = false
        }
    }
    val textIsEmpty = textFieldState.text.isBlank()
    val attachmentsIsEmpty = composer.selectedAttachments.isEmpty()
    val showStop = isLoading && textIsEmpty && attachmentsIsEmpty

    val canSend = (textFieldState.text.isNotBlank() || composer.selectedAttachments.isNotEmpty()) && isModelValid && !isSwitching
            && composer.selectedAttachments.none { it.localPath == null && (it.type == "image" || it.type == "file") }
    val isActionable = (isLoading || canSend || composer.pendingSend) && !isSwitching
    FloatingActionButton(
        onClick = {
            if (isSwitching) return@FloatingActionButton
            if (showStop) onStopGeneration()
            else if (composer.pendingSend) {
                haptics.selection()
                composer.pendingSend = false
            }
            else if (canSend) {
                // Haptic = button touch feel, fires on every tap regardless of whether the send
                // is immediate or deferred behind attachment processing.
                haptics.action()
                if (anyProcessing) {
                    composer.pendingSend = true
                } else {
                    if (onSendMessage(textFieldState.text.toString(), composer.selectedAttachments)) {
                        composer.clearAttachments()
                        textFieldState.edit { replace(0, length, "") }
                        onCollapse()
                    }
                }
            }
        },
        containerColor = animateColorAsState(if (isActionable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, tween(400), label = "fabContainer").value,
        contentColor = animateColorAsState(if (isActionable) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, tween(400), label = "fabContent").value,
        modifier = Modifier.size(46.dp),
        shape = CircleShape,
        elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp)
    ) {
        val fabIcon = when {
            composer.pendingSend -> "pending"
            showStop -> "stop"
            else -> "send"
        }
        AnimatedContent(
            targetState = fabIcon,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
            label = "fabIcon"
        ) { state ->
            when (state) {
                "pending" -> CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                "stop" -> Icon(Icons.Default.Stop, stringResource(R.string.action), modifier = Modifier.size(24.dp))
                else -> Icon(Icons.Default.ArrowUpward, stringResource(R.string.action), modifier = Modifier.size(24.dp))
            }
        }
    }
}
