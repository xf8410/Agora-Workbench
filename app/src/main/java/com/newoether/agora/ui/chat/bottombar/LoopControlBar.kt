package com.newoether.agora.ui.chat.bottombar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.local.LoopEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

@Composable
internal fun LoopControlBar(
    loop: LoopEntity,
    isRunning: Boolean,
    onStop: () -> Unit,
) {
    var now by remember(loop.conversationId, loop.nextFireAt) {
        mutableLongStateOf(System.currentTimeMillis())
    }
    LaunchedEffect(loop.conversationId, loop.nextFireAt, isRunning) {
        while (isActive && !isRunning) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    val remainingMs = (loop.nextFireAt - now).coerceAtLeast(0L)
    val remainingText = remember(remainingMs / 1_000L) { formatRemaining(remainingMs) }
    val status = if (isRunning) {
        stringResource(R.string.loop_running)
    } else {
        stringResource(R.string.loop_next_in, remainingText)
    }
    val cycle = loop.maxCycles?.let {
        stringResource(R.string.loop_cycle, loop.cycleCount, it)
    }

    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 6.dp, bottomEnd = 6.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Icon(Icons.Default.Repeat, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = listOfNotNull(status, cycle).joinToString(" · "),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onStop, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.StopCircle,
                    contentDescription = stringResource(R.string.loop_stop),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

private fun formatRemaining(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}
