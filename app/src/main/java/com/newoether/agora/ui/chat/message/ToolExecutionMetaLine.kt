package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.newoether.agora.R
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.ToolExecutionPresentation
import com.newoether.agora.model.ToolExecutionStatus
import com.newoether.agora.ui.theme.ChatType

/** Small, stable metadata row shared by compact and timeline tool cards. */
@Composable
internal fun ToolExecutionMetaLine(
    segment: MessageSegment,
    messageStatus: MessageStatus,
) {
    val status = ToolExecutionPresentation.status(segment, messageStatus) ?: return
    val durationMs = ToolExecutionPresentation.durationMs(segment)
    val statusText = when (status) {
        ToolExecutionStatus.RUNNING -> stringResource(R.string.tool_status_running)
        ToolExecutionStatus.SUCCESS -> stringResource(R.string.tool_status_success)
        ToolExecutionStatus.FAILED -> stringResource(R.string.tool_status_failed)
        ToolExecutionStatus.TIMED_OUT -> stringResource(R.string.tool_status_timed_out)
        ToolExecutionStatus.STOPPED -> stringResource(R.string.tool_status_stopped)
        ToolExecutionStatus.INTERRUPTED -> stringResource(R.string.tool_status_interrupted)
    }
    val durationText = durationMs?.let {
        if (it < 1_000L) stringResource(R.string.tool_duration_under_second)
        else stringResource(R.string.tool_duration_seconds, it / 1_000.0)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = statusText,
            style = ChatType.metaNormal,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
        if (durationText != null) {
            Text(
                text = durationText,
                style = ChatType.metaNormal,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}
