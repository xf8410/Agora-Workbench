package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.ui.theme.ChatType

// Tool detail sheet content, moved verbatim out of MessageItemTimeline.kt so the
// segment-detail surface owns its own rendering. Pure code-motion, behavior unchanged.

@Composable
internal fun ToolDetailContent(seg: MessageSegment) {
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
