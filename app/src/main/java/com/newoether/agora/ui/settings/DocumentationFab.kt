package com.newoether.agora.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Compatibility shim for settings pages compiled against older call sites.
 * Documentation entries are no longer rendered; keeping this symbol avoids breaking
 * downstream overlays while those call sites are removed in a later source cleanup.
 */
@Composable
fun DocumentationFab(docPath: String, modifier: Modifier = Modifier) {
    // Intentionally empty. Documentation is no longer exposed from settings.
}
