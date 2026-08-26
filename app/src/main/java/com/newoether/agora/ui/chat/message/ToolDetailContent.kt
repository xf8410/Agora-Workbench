package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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

/**
 * Tool detail sheet content: Arguments / Result sections with a stable
 * [ToolExecutionMetaLine] status row pinned at the top.
 *
 * The status row lives here (not inside each tool card) so its height can never
 * interleave with the section content below — previously the meta line rendered
 * inside [CompactSegmentBlock] right under the summary, and during streaming the
 * summary's single-line ellipsis could visually collide with it (text overlap).
 */
@Composable
internal fun ToolDetailContent(seg: MessageSegment) {
    val status = ToolExecutionPresentation.status(seg, MessageStatus.SUCCESS)
    if (status != null) {
        ToolExecutionMetaLine(
            segment = seg,
            messageStatus = MessageStatus.SUCCESS,
        )
        Spacer(modifier = Modifier.height(12.dp))
    }

    val args = seg.toolArgs
    if (!args.isNullOrBlank() && args != "{}") {
        Text(
            stringResource(R.string.arguments_label),
            style = ChatType.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        JsonOrPlainView(args)
        Spacer(modifier = Modifier.height(16.dp))
    }

    Text(
        stringResource(R.string.result_label),
        style = ChatType.meta,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(4.dp))
    val result = seg.toolResult
    if (result != null && result.isNotEmpty()) {
        JsonOrPlainView(result)
    } else {
        Text(
            text = stringResource(R.string.tool_calling_ellipsis),
            style = ChatType.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun segmentDetailTitle(
    seg: MessageSegment,
    detailSegments: List<MessageSegment>,
    detailIndex: Int
): String {
    return when (seg.type) {
        "tool" -> toolDisplayName(seg.toolName)
        "transcription" -> transcriptionLabel(detailSegments, detailIndex)
        else -> stringResource(R.string.tool_thinking)
    }
}
