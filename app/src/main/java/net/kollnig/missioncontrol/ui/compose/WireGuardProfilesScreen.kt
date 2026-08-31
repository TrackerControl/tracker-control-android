/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * TrackerControl is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with TrackerControl. If not, see <http://www.gnu.org/licenses/>.
 */
package net.kollnig.missioncontrol.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.kollnig.missioncontrol.R

/** Immutable, non-secret presentation data for one WireGuard profile row. */
@Immutable
data class WireGuardProfileRow(
    val id: String,
    val name: String,
    val summary: String?,
    val active: Boolean,
    val activeLabel: String
)

/** Immutable presentation model for the WireGuard profiles screen. */
@Immutable
data class WireGuardProfilesScreenModel(
    val rows: List<WireGuardProfileRow>,
    val addContentDescription: String = ""
)

/** Callback boundary for dialogs and persistence owned by ActivityWireGuardProfiles. */
interface WireGuardProfilesScreenCallbacks {
    fun onAdd()
    fun onEdit(id: String)
    fun onDelete(id: String)
}

/** Handle used by ActivityWireGuardProfiles to refresh the Compose model. */
class WireGuardProfilesScreenController internal constructor(
    private val state: MutableState<WireGuardProfilesScreenModel>
) {
    fun update(model: WireGuardProfilesScreenModel) {
        state.value = model
    }
}

/** Java-facing entry point for the incrementally migrated WireGuard profiles screen. */
object WireGuardProfilesScreen {
    @JvmStatic
    fun install(
        composeView: ComposeView,
        initialModel: WireGuardProfilesScreenModel,
        callbacks: WireGuardProfilesScreenCallbacks
    ): WireGuardProfilesScreenController {
        val state = mutableStateOf(initialModel)
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        composeView.setContent {
            TrackerControlTheme {
                WireGuardProfilesScreenContent(state.value, callbacks)
            }
        }
        return WireGuardProfilesScreenController(state)
    }
}

@Composable
internal fun WireGuardProfilesScreenContent(
    model: WireGuardProfilesScreenModel,
    callbacks: WireGuardProfilesScreenCallbacks
) {
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentWindowInsets = WindowInsets.safeDrawing,
        floatingActionButton = {
            FloatingActionButton(
                onClick = callbacks::onAdd,
                modifier = Modifier.testTag("wg-profile-add"),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(
                    painter = painterResource(android.R.drawable.ic_input_add),
                    contentDescription = model.addContentDescription.ifEmpty {
                        stringResource(R.string.setting_wg_profile_save)
                    }
                )
            }
        }
    ) { contentPadding ->
        if (model.rows.isEmpty()) {
            EmptyWireGuardProfilesContent(contentPadding)
        } else {
            WireGuardProfilesList(model.rows, contentPadding, callbacks)
        }
    }
}

@Composable
private fun EmptyWireGuardProfilesContent(contentPadding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .testTag("wg-profile-empty"),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.msg_wg_profile_empty),
            modifier = Modifier.padding(32.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WireGuardProfilesList(
    rows: List<WireGuardProfileRow>,
    contentPadding: PaddingValues,
    callbacks: WireGuardProfilesScreenCallbacks
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        // Leave room for the FAB so the last row can be scrolled clear of it.
        contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp)
    ) {
        itemsIndexed(
            items = rows,
            key = { _, row -> row.id }
        ) { index, row ->
            WireGuardProfileListItem(row, callbacks)
            if (index < rows.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.testTag("wg-profile-divider"),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
        }
    }
}

@Composable
private fun WireGuardProfileListItem(
    row: WireGuardProfileRow,
    callbacks: WireGuardProfilesScreenCallbacks
) {
    val editDescription = stringResource(R.string.accessibility_wg_profile_edit, row.name)
    val deleteDescription = stringResource(R.string.accessibility_wg_profile_delete, row.name)
    ListItem(
        headlineContent = {
            Text(
                text = row.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge
            )
        },
        supportingContent = if (!row.summary.isNullOrEmpty() || row.active) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    row.summary?.takeIf { it.isNotEmpty() }?.let { summary ->
                        Text(
                            text = summary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (row.active) {
                        Text(
                            text = row.activeLabel,
                            modifier = Modifier.testTag("wg-profile-active-${row.id}"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        } else {
            null
        },
        trailingContent = {
            IconButton(
                onClick = { callbacks.onDelete(row.id) },
                modifier = Modifier.testTag("wg-profile-delete-${row.id}")
            ) {
                Icon(
                    painter = painterResource(android.R.drawable.ic_menu_delete),
                    contentDescription = deleteDescription
                )
            }
        },
        // Name the action with onClickLabel rather than a row-level
        // contentDescription: overriding the description on this merging row
        // would hide the summary and the "Active" marker from TalkBack.
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = editDescription) { callbacks.onEdit(row.id) }
            .testTag("wg-profile-row-${row.id}")
    )
}

@Preview(showBackground = true)
@Composable
private fun WireGuardProfilesScreenPreview() {
    TrackerControlTheme {
        WireGuardProfilesScreenContent(
            model = WireGuardProfilesScreenModel(
                rows = listOf(
                    WireGuardProfileRow(
                        id = "preview-profile",
                        name = "Example VPN",
                        summary = "vpn.example.test:51820",
                        active = true,
                        activeLabel = "Active"
                    ),
                    WireGuardProfileRow(
                        id = "preview-secondary",
                        name = "Backup VPN",
                        summary = null,
                        active = false,
                        activeLabel = "Active"
                    )
                ),
                addContentDescription = "Add profile"
            ),
            callbacks = object : WireGuardProfilesScreenCallbacks {
                override fun onAdd() = Unit
                override fun onEdit(id: String) = Unit
                override fun onDelete(id: String) = Unit
            }
        )
    }
}
