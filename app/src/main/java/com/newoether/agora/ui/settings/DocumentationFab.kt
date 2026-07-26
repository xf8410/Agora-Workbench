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
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import com.newoether.agora.R

@Composable
fun DocumentationFab(docPath: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val langTag = java.util.Locale.getDefault().toLanguageTag()
    val baseUrl = "https://newo-ether.github.io/Agora/"
    // Map the resolved locale to the docs URL prefix.
    // "en" and anything unrecognised → root (English); each supported
    // language maps to its own subdirectory under the docs site.
    val langPrefix = when {
        langTag == "zh-Hant"  -> "zh-Hant/"
        langTag.startsWith("zh") -> "zh/"
        else -> {
            val lang = java.util.Locale.getDefault().language
            when (lang) {
                "es", "fr", "de", "ru", "ja", "ko", "ar" -> "$lang/"
                "pt" -> "pt-BR/"
                else -> ""  // en or unknown → root (English)
            }
        }
    }

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
        contentAlignment = Alignment.Center
    ) {
        FloatingActionButton(
            onClick = {
                val page = docPath.removeSuffix(".md") + "/"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$baseUrl$langPrefix$page"))
                context.startActivity(intent)
            },
            shape = RoundedCornerShape(50),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            elevation = FloatingActionButtonDefaults.elevation(4.dp, 4.dp),
            interactionSource = interactionSource,
            modifier = Modifier.width(animW).height(animH)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, modifier = Modifier.scale(contentScale))
                Spacer(Modifier.width(spacerW))
                Text(stringResource(R.string.documentation), maxLines = 1, modifier = Modifier.scale(contentScale))
            }
        }
    }
}
