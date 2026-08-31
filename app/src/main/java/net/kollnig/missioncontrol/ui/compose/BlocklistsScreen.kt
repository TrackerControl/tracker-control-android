/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.kollnig.missioncontrol.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.kollnig.missioncontrol.R

/** Immutable presentation data for one blocklist row. */
@Immutable
data class BlocklistRow(
    val uuid: String,
    val url: String,
    val lastUpdate: String?,
    val error: String?,
    val enabled: Boolean,
    val enableContentDescription: String,
    val deleteContentDescription: String
)

/** Immutable presentation model for the Blocklists screen. */
@Immutable
data class BlocklistsScreenModel(
    val rows: List<BlocklistRow>
)

/** Callback boundary for persistence and dialogs owned by ActivityBlocklists. */
interface BlocklistsScreenCallbacks {
    fun onAdd()
    fun onEdit(uuid: String)
    fun onEnabledChanged(uuid: String, enabled: Boolean)
    fun onDelete(uuid: String)
}

/** Handle used by ActivityBlocklists to refresh the Compose model. */
class BlocklistsScreenController internal constructor(
    private val state: MutableState<BlocklistsScreenModel>
) {
    fun update(model: BlocklistsScreenModel) {
        state.value = model
    }
}

/** Java-facing entry point for the incrementally migrated Blocklists screen. */
object BlocklistsScreen {
    @JvmStatic
    fun install(
        composeView: ComposeView,
        initialModel: BlocklistsScreenModel,
        callbacks: BlocklistsScreenCallbacks
    ): BlocklistsScreenController {
        val state = mutableStateOf(initialModel)
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        composeView.setContent {
            TrackerControlTheme {
                BlocklistsScreenContent(state.value, callbacks)
            }
        }
        return BlocklistsScreenController(state)
    }
}

@Composable
internal fun BlocklistsScreenContent(
    model: BlocklistsScreenModel,
    callbacks: BlocklistsScreenCallbacks
) {
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentWindowInsets = WindowInsets.safeDrawing,
        floatingActionButton = {
            FloatingActionButton(
                onClick = callbacks::onAdd,
                modifier = Modifier.testTag("blocklist-add"),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(
                    painter = painterResource(android.R.drawable.ic_input_add),
                    contentDescription = stringResource(R.string.title_add_blocklist)
                )
            }
        }
    ) { contentPadding ->
        if (model.rows.isEmpty()) {
            EmptyBlocklistsContent(contentPadding)
        } else {
            BlocklistsList(model.rows, contentPadding, callbacks)
        }
    }
}

@Composable
private fun EmptyBlocklistsContent(contentPadding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .testTag("blocklist-empty"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                painter = painterResource(android.R.drawable.ic_input_add),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.summary_manage_blocklists),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun BlocklistsList(
    rows: List<BlocklistRow>,
    contentPadding: PaddingValues,
    callbacks: BlocklistsScreenCallbacks
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp)
    ) {
        itemsIndexed(
            items = rows,
            key = { _, row -> row.uuid }
        ) { index, row ->
            BlocklistListItem(row, callbacks)
            if (index < rows.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.testTag("blocklist-divider"),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
        }
    }
}

@Composable
private fun BlocklistListItem(
    row: BlocklistRow,
    callbacks: BlocklistsScreenCallbacks
) {
    ListItem(
        headlineContent = {
            Text(
                text = row.url,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge
            )
        },
        supportingContent = if (row.lastUpdate != null || row.error != null) {
            {
                Column {
                    row.lastUpdate?.let { update ->
                        Text(
                            text = update,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    row.error?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        } else {
            null
        },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Switch(
                    checked = row.enabled,
                    onCheckedChange = { enabled ->
                        callbacks.onEnabledChanged(row.uuid, enabled)
                    },
                    modifier = Modifier
                        .testTag("blocklist-switch-${row.uuid}")
                        .semantics {
                            contentDescription = row.enableContentDescription
                        }
                )
                IconButton(
                    onClick = { callbacks.onDelete(row.uuid) },
                    modifier = Modifier.testTag("blocklist-delete-${row.uuid}")
                ) {
                    Icon(
                        painter = painterResource(android.R.drawable.ic_menu_delete),
                        contentDescription = row.deleteContentDescription
                    )
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable { callbacks.onEdit(row.uuid) }
            .testTag("blocklist-row-${row.uuid}")
    )
}

@Preview(showBackground = true)
@Composable
private fun BlocklistsScreenPreview() {
    TrackerControlTheme {
        BlocklistsScreenContent(
            model = BlocklistsScreenModel(
                rows = listOf(
                    BlocklistRow(
                        uuid = "example-uuid",
                        url = "https://example.com/hosts.txt",
                        lastUpdate = "Last updated: 30 August 2026, 12:00",
                        error = null,
                        enabled = true,
                        enableContentDescription = "Enable blocklist https://example.com/hosts.txt",
                        deleteContentDescription = "Delete blocklist"
                    ),
                    BlocklistRow(
                        uuid = "error-uuid",
                        url = "https://mirror.example/hosts.txt",
                        lastUpdate = null,
                        error = "Download failed",
                        enabled = false,
                        enableContentDescription = "Enable blocklist https://mirror.example/hosts.txt",
                        deleteContentDescription = "Delete blocklist"
                    )
                )
            ),
            callbacks = object : BlocklistsScreenCallbacks {
                override fun onAdd() = Unit
                override fun onEdit(uuid: String) = Unit
                override fun onEnabledChanged(uuid: String, enabled: Boolean) = Unit
                override fun onDelete(uuid: String) = Unit
            }
        )
    }
}
