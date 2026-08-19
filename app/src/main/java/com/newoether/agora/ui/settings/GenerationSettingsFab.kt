package com.newoether.agora.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.SettingsManager
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
internal fun GenerationSettingsFab(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val settings = remember(context) { SettingsManager(context) }
    val scope = rememberCoroutineScope()

    val temperature by settings.defaultTemperature.collectAsState(initial = null)
    val maxTokens by settings.defaultMaxTokens.collectAsState(initial = null)
    val topP by settings.defaultTopP.collectAsState(initial = null)
    val frequencyPenalty by settings.defaultFrequencyPenalty.collectAsState(initial = null)
    val presencePenalty by settings.defaultPresencePenalty.collectAsState(initial = null)
    val contextWindow by settings.maxContextWindow.collectAsState(initial = 20)
    val thinkingEnabled by settings.thinkingEnabled.collectAsState(initial = true)
    val thinkingLevel by settings.thinkingLevel.collectAsState(initial = "medium")

    var showSummary by remember { mutableStateOf(false) }
    var showResetConfirmation by remember { mutableStateOf(false) }

    ExtendedFloatingActionButton(
        onClick = { showSummary = true },
        modifier = modifier,
        icon = { Icon(Icons.Default.Tune, contentDescription = null) },
        text = { Text(stringResource(R.string.generation_config_summary_action)) }
    )

    if (showSummary) {
        AlertDialog(
            onDismissRequest = { showSummary = false },
            title = { Text(stringResource(R.string.generation_config_summary_title)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SummaryRow(stringResource(R.string.context_window), contextWindow.toString())
                    SummaryRow(
                        stringResource(R.string.gen_thinking_enabled),
                        if (thinkingEnabled) thinkingLevel else stringResource(R.string.thinking_control_off)
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    SummaryRow(stringResource(R.string.gen_temperature), formatOptional(temperature))
                    SummaryRow(stringResource(R.string.gen_max_tokens), maxTokens?.toString() ?: stringResource(R.string.gen_not_specified))
                    SummaryRow(stringResource(R.string.gen_top_p), formatOptional(topP))
                    SummaryRow(stringResource(R.string.gen_frequency_penalty), formatOptional(frequencyPenalty))
                    SummaryRow(stringResource(R.string.gen_presence_penalty), formatOptional(presencePenalty))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showSummary = false
                    showResetConfirmation = true
                }) {
                    Text(stringResource(R.string.generation_reset_defaults_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSummary = false }) {
                    Text(stringResource(R.string.provider_close))
                }
            }
        )
    }

    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            title = { Text(stringResource(R.string.generation_reset_defaults_title)) },
            text = { Text(stringResource(R.string.generation_reset_defaults_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showResetConfirmation = false
                    scope.launch {
                        settings.saveDefaultTemperature(null)
                        settings.saveDefaultMaxTokens(null)
                        settings.saveDefaultTopP(null)
                        settings.saveDefaultFrequencyPenalty(null)
                        settings.saveDefaultPresencePenalty(null)
                    }
                }) {
                    Text(stringResource(R.string.generation_reset_defaults_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun formatOptional(value: Float?): String =
    value?.let { String.format(Locale.US, "%.2f", it) }
        ?: stringResource(R.string.gen_not_specified)
