package com.newoether.agora.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Compatibility entry point retained for settings pages that still pass the former documentation
 * path. Workbench does not expose upstream documentation. The generation page uses this slot for
 * its local request-summary and reset controls; all other former documentation buttons stay hidden.
 */
@Composable
fun DocumentationFab(docPath: String, modifier: Modifier = Modifier) {
    if (docPath == "generation.md") {
        GenerationSettingsFab(modifier)
    }
}
