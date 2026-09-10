package com.newoether.agora.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.newoether.agora.R
import com.newoether.agora.data.FavoriteSitesStore
import com.newoether.agora.data.FavoriteSiteItem
import com.newoether.agora.data.SiteCategory
import com.newoether.agora.viewmodel.ChatViewModel

/**
 * Favorite-sites management page: the user's quick-launch list of links (project repos,
 * collaborators, references). Tap a card to open the site in the browser; tap the edit
 * icon to change or delete it. The same list is maintained by the assistant through the
 * sites_* tools, and new entries can always be added — the built-in defaults are only
 * initial data.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsFavoriteSitesPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { FavoriteSitesStore(context.filesDir) }
    var items by remember { mutableStateOf(store.list()) }
    var filter by rememberSaveable { mutableStateOf("all") }
    var dialogItem by remember { mutableStateOf<FavoriteSiteItem?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var openFailed by remember { mutableStateOf(false) }

    fun refresh() { items = store.list() }

    fun openSite(item: FavoriteSiteItem) {
        val opened = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, item.url.toUri()))
        }.isSuccess
        if (!opened) openFailed = true
    }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_sites),
        onBack = onBack,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text(stringResource(R.string.sites_add)) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                onClick = { showAddDialog = true }
            )
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = filter == "all",
                onClick = { filter = "all" },
                label = { Text(stringResource(R.string.sites_filter_all)) }
            )
            FilterChip(
                selected = filter == "mine",
                onClick = { filter = "mine" },
                label = { Text(stringResource(R.string.sites_cat_mine)) }
            )
            FilterChip(
                selected = filter == "external",
                onClick = { filter = "external" },
                label = { Text(stringResource(R.string.sites_cat_external)) }
            )
            FilterChip(
                selected = filter == "other",
                onClick = { filter = "other" },
                label = { Text(stringResource(R.string.sites_cat_other)) }
            )
        }

        val visible = items.filter { filter == "all" || it.category.name.lowercase() == filter }
        if (visible.isEmpty()) {
            Text(
                text = stringResource(R.string.sites_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 24.dp)
            )
        }
        visible.forEach { item ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                onClick = { openSite(item) }
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = item.url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1
                        )
                        if (item.note.isNotBlank()) {
                            Text(
                                text = item.note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                    }
                    IconButton(onClick = { dialogItem = item }) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = stringResource(R.string.sites_edit),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = { openSite(item) }) {
                        Icon(
                            Icons.Default.OpenInNew,
                            contentDescription = stringResource(R.string.sites_open),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        if (openFailed) {
            AlertDialog(
                onDismissRequest = { openFailed = false },
                title = { Text(stringResource(R.string.sites_open_failed)) },
                confirmButton = {
                    TextButton(onClick = { openFailed = false }) {
                        Text(stringResource(R.string.sites_cancel))
                    }
                }
            )
        }

        if (showAddDialog) {
            SiteEditDialog(
                initial = null,
                onDismiss = { showAddDialog = false },
                onSave = { title, url, category, note ->
                    if (title.isNotBlank() && url.isNotBlank()) {
                        store.add(title, url, category, note)
                        refresh()
                    }
                    showAddDialog = false
                },
                onDelete = null
            )
        }
        dialogItem?.let { current ->
            SiteEditDialog(
                initial = current,
                onDismiss = { dialogItem = null },
                onSave = { title, url, category, note ->
                    store.update(current.id, title = title, url = url, category = category, note = note)
                    refresh()
                    dialogItem = null
                },
                onDelete = {
                    store.remove(current.id)
                    refresh()
                    dialogItem = null
                }
            )
        }
    }
}

@Composable
private fun SiteEditDialog(
    initial: FavoriteSiteItem?,
    onDismiss: () -> Unit,
    onSave: (title: String, url: String, category: SiteCategory, note: String) -> Unit,
    onDelete: (() -> Unit)?
) {
    var title by remember(initial) { mutableStateOf(initial?.title.orEmpty()) }
    var url by remember(initial) { mutableStateOf(initial?.url.orEmpty()) }
    var category by remember(initial) { mutableStateOf(initial?.category ?: SiteCategory.OTHER) }
    var note by remember(initial) { mutableStateOf(initial?.note.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.sites_add else R.string.sites_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.sites_title_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.sites_url_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = category == SiteCategory.MINE,
                        onClick = { category = SiteCategory.MINE },
                        label = { Text(stringResource(R.string.sites_cat_mine)) }
                    )
                    FilterChip(
                        selected = category == SiteCategory.EXTERNAL,
                        onClick = { category = SiteCategory.EXTERNAL },
                        label = { Text(stringResource(R.string.sites_cat_external)) }
                    )
                    FilterChip(
                        selected = category == SiteCategory.OTHER,
                        onClick = { category = SiteCategory.OTHER },
                        label = { Text(stringResource(R.string.sites_cat_other)) }
                    )
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.sites_note_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(title, url, category, note) }) {
                Text(stringResource(R.string.sites_save))
            }
        },
        dismissButton = {
            if (onDelete != null) {
                TextButton(onClick = onDelete) {
                    Text(
                        stringResource(R.string.sites_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.sites_cancel))
                }
            }
        }
    )
}
