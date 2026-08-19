package com.newoether.agora.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R

private fun documentationUri(docPath: String): Uri {
    val langTag = java.util.Locale.getDefault().toLanguageTag()
    val langPrefix = when {
        langTag == "zh-Hant" -> "zh-Hant/"
        langTag.startsWith("zh") -> "zh/"
        else -> when (val lang = java.util.Locale.getDefault().language) {
            "es", "fr", "de", "ru", "ja", "ko", "ar" -> "$lang/"
            "pt" -> "pt-BR/"
            else -> ""
        }
    }
    val page = docPath.removeSuffix(".md") + "/"
    return Uri.parse("https://newo-ether.github.io/Agora/$langPrefix$page")
}

private fun openDocumentation(context: android.content.Context, docPath: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, documentationUri(docPath)))
}

/** Compact app-bar action. Prefer this on scrolling settings pages so docs never cover controls. */
@Composable
fun DocumentationAction(docPath: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    IconButton(
        onClick = { openDocumentation(context, docPath) },
        modifier = modifier,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.MenuBook,
            contentDescription = stringResource(R.string.documentation),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

/** Legacy bottom action retained for pages that explicitly opt into an overlay. */
@Composable
fun DocumentationFab(docPath: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val targetW = if (isPressed) 240.dp else 200.dp
    val targetH = if (isPressed) 56.dp else 48.dp
    val animW by animateDpAsState(targetW, spring(stiffness = 400f, dampingRatio = 0.25f), "fabW")
    val animH by animateDpAsState(targetH, spring(stiffness = 400f, dampingRatio = 0.25f), "fabH")
    val contentScale by animateFloatAsState(if (isPressed) 1.1f else 1f, spring(stiffness = 400f, dampingRatio = 0.25f), label = "contentS")
    val spacerW by animateDpAsState(if (isPressed) 16.dp else 10.dp, spring(stiffness = 400f, dampingRatio = 0.25f), label = "spacerW")

    Box(
        modifier = modifier.navigationBarsPadding().height(56.dp),
        contentAlignment = Alignment.Center,
    ) {
        FloatingActionButton(
            onClick = { openDocumentation(context, docPath) },
            shape = RoundedCornerShape(50),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            elevation = FloatingActionButtonDefaults.elevation(4.dp, 4.dp),
            interactionSource = interactionSource,
            modifier = Modifier.width(animW).height(animH),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, modifier = Modifier.scale(contentScale))
                Spacer(Modifier.width(spacerW))
                Text(stringResource(R.string.documentation), maxLines = 1, modifier = Modifier.scale(contentScale))
            }
        }
    }
}
