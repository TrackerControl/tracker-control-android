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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.kollnig.missioncontrol.R
import net.kollnig.missioncontrol.data.AppProtectionState

/** Material 3's disabled-content opacity, used for rows that cannot be tapped. */
private const val DISABLED_ROW_ALPHA = 0.38f

/** Immutable pause presentation calculated by ProtectionActivity. */
data class PauseSection(
    val status: String,
    val sharedUidText: String?,
    val actionText: String,
    val lockdownText: String?
)

/** Immutable blocked-tracker presentation calculated by ProtectionActivity. */
data class BlockedState(
    val available: Boolean,
    val message: String?,
    val categories: List<CategoryModel>
)

/** One blocked tracker category and its companies. */
data class CategoryModel(
    val key: String,
    val name: String,
    val title: String,
    val lastContact: String?,
    val explainer: String?,
    val switchChecked: Boolean,
    val switchEnabled: Boolean,
    val switchDescription: String,
    val companies: List<CompanyModel>
)

/** One tracker company row. */
data class CompanyModel(
    val blockingKey: String,
    val name: String,
    val lastSeen: String?,
    val status: String,
    val statusBlocked: Boolean,
    val actionable: Boolean,
    val actionDescription: String
)

/** One selectable per-app protection state. */
data class StateOption(
    val value: AppProtectionState,
    val title: String,
    val description: String,
    val selected: Boolean,
    val show: Boolean
)

/** One selectable per-app routing option. */
data class RouteOption(
    val tunnelled: Boolean,
    val title: String,
    val description: String,
    val selected: Boolean
)

/** The route control is either unavailable or offers its two choices. */
sealed interface RouteModel {
    data class Unavailable(val message: String) : RouteModel
    data class Available(val options: List<RouteOption>) : RouteModel
}

/** Immutable presentation model for the complete protection body. */
data class ProtectionScreenModel(
    val pause: PauseSection?,
    val blocked: BlockedState,
    val stateOptions: List<StateOption>,
    val route: RouteModel
)

/** Callbacks for actions whose implementation remains in ProtectionActivity. */
interface ProtectionScreenCallbacks {
    fun onPauseResume()
    fun onStateSelected(value: AppProtectionState)
    fun onRouteSelected(tunnelled: Boolean)
    fun onCategoryToggle(categoryKey: String, checked: Boolean)
    fun onCompanyClick(blockingKey: String)
}

/** Handle used by Java to update Compose without moving state or lifecycle logic. */
class ProtectionScreenController internal constructor(
    private val state: MutableState<ProtectionScreenModel>
) {
    fun update(model: ProtectionScreenModel) {
        state.value = model
    }
}

/** Java-facing entry point for the incrementally migrated protection body. */
object ProtectionScreen {
    @JvmStatic
    fun install(
        composeView: ComposeView,
        initialModel: ProtectionScreenModel,
        callbacks: ProtectionScreenCallbacks
    ): ProtectionScreenController {
        val state = mutableStateOf(initialModel)
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        composeView.setContent {
            TrackerControlTheme {
                ProtectionScreenContent(state.value, callbacks)
            }
        }
        return ProtectionScreenController(state)
    }
}

@Composable
internal fun ProtectionScreenContent(
    model: ProtectionScreenModel,
    callbacks: ProtectionScreenCallbacks
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        model.pause?.let { pause ->
            item(key = "pause") {
                PauseContent(pause, callbacks::onPauseResume)
            }
        }

        item(key = "blocked-heading") {
            DetailsSectionHeading(
                text = androidx.compose.ui.res.stringResource(R.string.protection_blocked_last_24h)
            )
        }
        if (!model.blocked.available) {
            model.blocked.message?.let { message ->
                item(key = "blocked-message") {
                    EmptyMessage(message)
                }
            }
        } else if (model.blocked.categories.isEmpty()) {
            model.blocked.message?.let { message ->
                item(key = "blocked-empty") {
                    EmptyMessage(message)
                }
            }
        } else {
            model.blocked.categories.forEach { category ->
                item(key = "category:${category.key}") {
                    CategoryRow(
                        category = category,
                        onToggle = { checked ->
                            callbacks.onCategoryToggle(category.key, checked)
                        }
                    )
                }
                itemsIndexed(
                    items = category.companies,
                    key = { _, company -> "company:${category.key}:${company.blockingKey}" }
                ) { index, company ->
                    CompanyRow(company, callbacks::onCompanyClick)
                    if (index < category.companies.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .testTag("protection-company-divider"),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                }
            }
        }

        item(key = "state-heading") {
            DetailsSectionHeading(
                text = androidx.compose.ui.res.stringResource(R.string.app_state_heading)
            )
        }
        model.stateOptions.filter { it.show }.forEach { option ->
            item(key = "state:${option.value}") {
                OptionRow(
                    title = option.title,
                    description = option.description,
                    selected = option.selected,
                    onClick = {
                        if (!option.selected)
                            callbacks.onStateSelected(option.value)
                    }
                )
            }
        }

        item(key = "route-heading") {
            DetailsSectionHeading(
                text = androidx.compose.ui.res.stringResource(R.string.protection_route_heading)
            )
        }
        when (val route = model.route) {
            is RouteModel.Unavailable -> item(key = "route-unavailable") {
                EmptyMessage(route.message)
            }

            is RouteModel.Available -> route.options.forEach { option ->
                item(key = "route:${option.tunnelled}") {
                    OptionRow(
                        title = option.title,
                        description = option.description,
                        selected = option.selected,
                        onClick = {
                            if (!option.selected)
                                callbacks.onRouteSelected(option.tunnelled)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PauseContent(
    pause: PauseSection,
    onPauseResume: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = pause.status,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        pause.sharedUidText?.let { sharedUidText ->
            Text(
                text = sharedUidText,
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Button(
            onClick = onPauseResume,
            modifier = Modifier
                .padding(top = 8.dp)
                .heightIn(min = 48.dp)
        ) {
            Text(text = pause.actionText)
        }
        pause.lockdownText?.let { lockdownText ->
            Text(
                text = lockdownText,
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyMessage(message: String) {
    Text(
        text = message,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun CategoryRow(
    category: CategoryModel,
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
                text = category.title.uppercase(locale),
                modifier = Modifier.semantics {
                    heading()
                    contentDescription = category.title
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            category.lastContact?.let { lastContact ->
                Text(
                    text = lastContact,
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            category.explainer?.let { explainer ->
                Text(
                    text = explainer,
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // The pre-Compose row labelled the switch "Block" next to it; without
        // it the bare toggle does not say what it turns on.
        Text(
            text = stringResource(R.string.title_block),
            modifier = Modifier
                .padding(end = 8.dp)
                .clearAndSetSemantics { },
            style = MaterialTheme.typography.labelLarge,
            color = if (category.switchEnabled) {
                MaterialTheme.colorScheme.onBackground
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Switch(
            checked = category.switchChecked,
            onCheckedChange = onToggle,
            enabled = category.switchEnabled,
            modifier = Modifier.semantics {
                contentDescription = category.switchDescription
            },
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
private fun CompanyRow(
    company: CompanyModel,
    onClick: (String) -> Unit
) {
    val rowModifier = if (company.actionable) {
        Modifier
            .fillMaxWidth()
            // Name the action with onClickLabel and let the merged children
            // announce themselves: a row-level contentDescription here would
            // swallow the last-seen time and the Blocked/Allowed status.
            .clickable(onClickLabel = company.actionDescription) {
                onClick(company.blockingKey)
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
            }
    } else {
        // The chips this row replaced were disabled here, which greyed them
        // out; keep that "you cannot act on this" cue.
        Modifier
            .fillMaxWidth()
            .alpha(DISABLED_ROW_ALPHA)
    }
    Column(
        modifier = rowModifier.padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = company.name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = company.lastSeen.orEmpty(),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = company.status,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (company.statusBlocked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.secondary
                }
            )
        }
    }
}

@Composable
private fun OptionRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                text = title,
                fontWeight = FontWeight.Bold
            )
        },
        supportingContent = {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingContent = {
            RadioButton(
                selected = selected,
                onClick = null
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton
            )
            .semantics(mergeDescendants = true) {
                this.selected = selected
            }
    )
}

@Preview(showBackground = true)
@Composable
private fun ProtectionScreenPreview() {
    TrackerControlTheme {
        ProtectionScreenContent(
            model = ProtectionScreenModel(
                pause = PauseSection(
                    status = "Protected",
                    sharedUidText = "Also applies to Example Companion.",
                    actionText = "Pause for 15 mins",
                    lockdownText = "Android does not expose the lockdown status here."
                ),
                blocked = BlockedState(
                    available = true,
                    message = null,
                    categories = listOf(
                        CategoryModel(
                            key = "analytics",
                            name = "analytics",
                            title = "Analytics",
                            lastContact = "last contact 3 minutes ago",
                            explainer = null,
                            switchChecked = true,
                            switchEnabled = true,
                            switchDescription = "Tracker blocking for category Analytics",
                            companies = listOf(
                                CompanyModel(
                                    blockingKey = "first",
                                    name = "Example Analytics",
                                    lastSeen = "3 minutes ago",
                                    status = "Blocked",
                                    statusBlocked = true,
                                    actionable = true,
                                    actionDescription = "Allow Example Analytics in Example App"
                                ),
                                CompanyModel(
                                    blockingKey = "second",
                                    name = "Example Ads",
                                    lastSeen = "5 minutes ago",
                                    status = "Allowed by you",
                                    statusBlocked = false,
                                    actionable = true,
                                    actionDescription = "Allow Example Ads in Example App"
                                )
                            )
                        )
                    )
                ),
                stateOptions = listOf(
                    StateOption(AppProtectionState.PROTECTED, "Protected", "Trackers are blocked and recorded.", true, true),
                    StateOption(AppProtectionState.TRACKERS_ALLOWED, "Trackers allowed", "Trackers are neither blocked nor recorded.", false, true),
                    StateOption(AppProtectionState.BYPASSED, "Bypass TrackerControl", "The app connects directly.", false, true)
                ),
                route = RouteModel.Available(
                    listOf(
                        RouteOption(true, "Through the remote VPN", "The remote VPN sees this app's traffic.", true),
                        RouteOption(false, "Directly from this device", "Tracker monitoring still applies.", false)
                    )
                )
            ),
            callbacks = object : ProtectionScreenCallbacks {
                override fun onPauseResume() = Unit
                override fun onStateSelected(value: AppProtectionState) = Unit
                override fun onRouteSelected(tunnelled: Boolean) = Unit
                override fun onCategoryToggle(categoryKey: String, checked: Boolean) = Unit
                override fun onCompanyClick(blockingKey: String) = Unit
            }
        )
    }
}
