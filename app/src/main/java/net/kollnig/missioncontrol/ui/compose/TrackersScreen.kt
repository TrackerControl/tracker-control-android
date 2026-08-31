/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.kollnig.missioncontrol.ui.compose

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.kollnig.missioncontrol.R

/** Immutable presentation state calculated by the Java tracker controller. */
data class TrackersScreenModel(
    val browserWarning: Boolean,
    val appStateTitle: String,
    val appStateValue: String?,
    val appStateHint: String,
    val appStateHintAccent: Boolean,
    val librarySummary: String,
    val rows: List<TrackersRow>,
    /** True while the tracker query runs; drives the pull-to-refresh spinner. */
    val isRefreshing: Boolean = false
)

/** Immutable row data; policy and persistence decisions stay in Java. */
sealed interface TrackersRow {
    val key: String

    data class Section(
        override val key: String,
        val categoryName: String,
        val title: String,
        val lastContact: String?,
        val explainer: String?,
        val switchChecked: Boolean,
        val switchEnabled: Boolean,
        val switchDescription: String
    ) : TrackersRow

    data class Company(
        override val key: String,
        val blockingKey: String,
        val name: String,
        val lastSeen: String?,
        val status: String,
        val statusBlocked: Boolean,
        val expanded: Boolean,
        val hosts: String,
        val uncertain: Boolean,
        val showAllowSwitch: Boolean,
        val allowSwitchLabel: String,
        val allowSwitchChecked: Boolean,
        val allowSwitchEnabled: Boolean,
        val sharedIpNote: String?,
        val showDivider: Boolean,
        val expandedDescription: String,
        val collapsedDescription: String
    ) : TrackersRow

    data class ShowMore(
        override val key: String,
        val text: String,
        val categoryName: String
    ) : TrackersRow
}

/** Callbacks for actions whose implementation remains in TrackersFragment. */
interface TrackersScreenCallbacks {
    fun onAppStateClick()
    fun onLibrariesClick()
    fun onSectionToggle(categoryName: String, checked: Boolean)
    fun onCompanyClick(blockingKey: String)
    fun onCompanyToggle(blockingKey: String, checked: Boolean)
    fun onShowMore(categoryName: String)

    /** Pull-to-refresh gesture; re-runs the tracker query for this app. */
    fun onRefresh()
}

/** Handle used by Java to update Compose without moving data/lifecycle logic. */
class TrackersScreenController internal constructor(
    private val state: MutableState<TrackersScreenModel>
) {
    fun update(model: TrackersScreenModel) {
        state.value = model
    }
}

/** Java-facing entry point for the incrementally migrated Trackers tab. */
object TrackersScreen {
    @JvmStatic
    fun install(
        composeView: ComposeView,
        initialModel: TrackersScreenModel,
        callbacks: TrackersScreenCallbacks
    ): TrackersScreenController {
        val state = mutableStateOf(initialModel)
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        composeView.setContent {
            TrackerControlTheme {
                TrackersScreenContent(state.value, callbacks)
            }
        }
        return TrackersScreenController(state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackersScreenContent(
    model: TrackersScreenModel,
    callbacks: TrackersScreenCallbacks
) {
    // isRefreshing also covers the initial async tracker query, which is what
    // the pre-Compose SwipeRefreshLayout spinner reported.
    PullToRefreshBox(
        isRefreshing = model.isRefreshing,
        onRefresh = callbacks::onRefresh,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TrackersList(model, callbacks)
    }
}

@Composable
private fun TrackersList(
    model: TrackersScreenModel,
    callbacks: TrackersScreenCallbacks
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        item(key = "header") {
            TrackersHeader(
                model = model,
                onAppStateClick = callbacks::onAppStateClick,
                onLibrariesClick = callbacks::onLibrariesClick
            )
        }
        items(model.rows, key = { it.key }) { row ->
            when (row) {
                is TrackersRow.Section -> TrackerSectionRow(
                    row = row,
                    onToggle = { checked -> callbacks.onSectionToggle(row.categoryName, checked) }
                )

                is TrackersRow.Company -> TrackerCompanyRow(
                    row = row,
                    onClick = { callbacks.onCompanyClick(row.blockingKey) },
                    onToggle = { checked -> callbacks.onCompanyToggle(row.blockingKey, checked) }
                )

                is TrackersRow.ShowMore -> TrackerShowMoreRow(
                    row = row,
                    onClick = { callbacks.onShowMore(row.categoryName) }
                )
            }
        }
    }
}

@Composable
private fun TrackersHeader(
    model: TrackersScreenModel,
    onAppStateClick: () -> Unit,
    onLibrariesClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (model.browserWarning) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            ) {
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.browsers_not_supported),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { heading() },
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 24.sp
                )
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.browsers_not_supported_explanation),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
        }

        DetailsSectionHeading(
            text = androidx.compose.ui.res.stringResource(R.string.block_tracking)
        )
        Text(
            text = androidx.compose.ui.res.stringResource(R.string.block_tracking_description),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge
        )

        TrackerSummaryRow(
            title = model.appStateTitle,
            value = model.appStateValue,
            hint = model.appStateHint,
            hintColour = if (model.appStateHintAccent) {
                colorResource(R.color.colorAccent)
            } else {
                MaterialTheme.colorScheme.primary
            },
            onClick = onAppStateClick
        )
        HeaderDivider()
        TrackerSummaryRow(
            title = androidx.compose.ui.res.stringResource(R.string.libraries_row_heading),
            value = model.librarySummary,
            hint = null,
            hintColour = MaterialTheme.colorScheme.onSurface,
            onClick = onLibrariesClick
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun TrackerSummaryRow(
    title: String,
    value: String?,
    hint: String?,
    hintColour: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            if (!value.isNullOrEmpty()) {
                Text(
                    text = value,
                    modifier = Modifier.padding(top = 2.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
            if (!hint.isNullOrEmpty()) {
                Text(
                    text = hint,
                    modifier = Modifier.padding(top = 2.dp),
                    color = hintColour,
                    fontSize = 12.sp
                )
            }
        }
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            modifier = Modifier
                .padding(start = 8.dp)
                .size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HeaderDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
        thickness = 1.dp
    )
}

@Composable
private fun TrackerSectionRow(
    row: TrackersRow.Section,
    onToggle: (Boolean) -> Unit
) {
    val configuration = LocalConfiguration.current
    @Suppress("DEPRECATION")
    val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        configuration.locales[0]
    } else {
        configuration.locale
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // TalkBack spells real uppercase out letter by letter, so keep the
            // visual casing but announce the untouched title.
            Text(
                text = row.title.uppercase(locale),
                modifier = Modifier.semantics {
                    heading()
                    contentDescription = row.title
                },
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            if (!row.lastContact.isNullOrEmpty()) {
                Text(
                    text = row.lastContact,
                    modifier = Modifier.padding(top = 2.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (!row.explainer.isNullOrEmpty()) {
                Text(
                    text = row.explainer,
                    modifier = Modifier.padding(top = 2.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        TrackerSwitch(
            checked = row.switchChecked,
            enabled = row.switchEnabled,
            contentDescription = row.switchDescription,
            onCheckedChange = onToggle
        )
    }
}

@Composable
private fun TrackerCompanyRow(
    row: TrackersRow.Company,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics {
                stateDescription = if (row.expanded) row.expandedDescription else row.collapsedDescription
            }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = row.name,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )
            Icon(
                painter = painterResource(if (row.expanded) {
                    R.drawable.ic_expand_less_black_24dp
                } else {
                    R.drawable.ic_expand_more_black_24dp
                }),
                contentDescription = null,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!row.lastSeen.isNullOrEmpty()) {
                Text(
                    text = row.lastSeen,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            Text(
                text = row.status,
                modifier = Modifier.weight(1f),
                color = if (row.statusBlocked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    colorResource(R.color.colorAccent)
                },
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }

        if (row.expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text(
                    text = row.hosts,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (row.uncertain) {
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.uncertain_entry),
                        modifier = Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Normal,
                        fontStyle = FontStyle.Italic
                    )
                }
                if (row.showAllowSwitch) {
                    TrackerSwitch(
                        checked = row.allowSwitchChecked,
                        enabled = row.allowSwitchEnabled,
                        contentDescription = row.allowSwitchLabel,
                        onCheckedChange = onToggle,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        label = row.allowSwitchLabel
                    )
                }
                if (!row.sharedIpNote.isNullOrEmpty()) {
                    Text(
                        text = row.sharedIpNote,
                        modifier = Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (row.showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 1.dp
            )
        }
    }
}

@Composable
private fun TrackerSwitch(
    checked: Boolean,
    enabled: Boolean,
    contentDescription: String,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null
) {
    Row(
        modifier = modifier
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .semantics { this.contentDescription = contentDescription },
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!label.isNullOrEmpty()) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onSecondary,
                checkedTrackColor = MaterialTheme.colorScheme.secondary,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline
            )
        )
    }
}

@Composable
private fun TrackerShowMoreRow(
    row: TrackersRow.ShowMore,
    onClick: () -> Unit
) {
    Text(
        text = row.text,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        color = MaterialTheme.colorScheme.primary
    )
}

@Preview(showBackground = true)
@Composable
private fun TrackersScreenPreview() {
    TrackerControlTheme {
        TrackersScreenContent(
            model = TrackersScreenModel(
                browserWarning = false,
                appStateTitle = "Protection",
                appStateValue = "Protected",
                appStateHint = "Tap to change protection",
                appStateHintAccent = true,
                librarySummary = "2 libraries detected",
                rows = listOf(
                    TrackersRow.Section(
                        key = "analytics",
                        categoryName = "Analytics",
                        title = "Analytics",
                        lastContact = "Last contact 3 minutes ago",
                        explainer = null,
                        switchChecked = true,
                        switchEnabled = true,
                        switchDescription = "Toggle blocking Analytics"
                    ),
                    TrackersRow.Company(
                        key = "example",
                        blockingKey = "example",
                        name = "Example Analytics",
                        lastSeen = "3 minutes ago",
                        status = "Blocked",
                        statusBlocked = true,
                        expanded = false,
                        hosts = "tracker.example",
                        uncertain = false,
                        showAllowSwitch = false,
                        allowSwitchLabel = "Allow Example Analytics",
                        allowSwitchChecked = false,
                        allowSwitchEnabled = true,
                        sharedIpNote = null,
                        showDivider = false,
                        expandedDescription = "Collapse Example Analytics",
                        collapsedDescription = "Expand Example Analytics"
                    )
                )
            ),
            callbacks = NoOpTrackersCallbacks
        )
    }
}

private object NoOpTrackersCallbacks : TrackersScreenCallbacks {
    override fun onAppStateClick() = Unit
    override fun onLibrariesClick() = Unit
    override fun onSectionToggle(categoryName: String, checked: Boolean) = Unit
    override fun onCompanyClick(blockingKey: String) = Unit
    override fun onCompanyToggle(blockingKey: String, checked: Boolean) = Unit
    override fun onShowMore(categoryName: String) = Unit
    override fun onRefresh() = Unit
}
