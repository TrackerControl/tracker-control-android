/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.kollnig.missioncontrol.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.kollnig.missioncontrol.R

/** Immutable presentation state calculated by the Java activity controller. */
data class LibrariesScreenModel(
    val explanation: String,
    val result: LibrariesResult?,
    val progress: LibrariesProgress?,
    val actionText: String,
    val actionEnabled: Boolean
)

/** Immutable detected-library result data; URLs are safe strings, not Android objects. */
data class LibrariesResult(
    val libraries: List<LibraryRow>,
    val rawText: String?,
    val disclaimer: String?
)

/** Immutable progress data; a null percentage represents queued/indeterminate work. */
data class LibrariesProgress(
    val text: String,
    val percent: Int?
)

/** One detected library and its optional, Java-owned website callback target. */
data class LibraryRow(
    val name: String,
    val website: String?
)

/** Callbacks for actions whose implementation remains in LibrariesActivity. */
interface LibrariesScreenCallbacks {
    fun onAnalyse()
    fun onWebsiteClick(website: String)
}

/** Handle used by Java to update Compose without moving state or lifecycle logic. */
class LibrariesScreenController internal constructor(
    private val state: MutableState<LibrariesScreenModel>
) {
    fun update(model: LibrariesScreenModel) {
        state.value = model
    }
}

/** Java-facing entry point for the incrementally migrated Libraries screen. */
object LibrariesScreen {
    @JvmStatic
    fun install(
        composeView: ComposeView,
        initialModel: LibrariesScreenModel,
        callbacks: LibrariesScreenCallbacks
    ): LibrariesScreenController {
        val state = mutableStateOf(initialModel)
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        composeView.setContent {
            TrackerControlTheme {
                LibrariesScreenContent(state.value, callbacks)
            }
        }
        return LibrariesScreenController(state)
    }
}

@Composable
internal fun LibrariesScreenContent(
    model: LibrariesScreenModel,
    callbacks: LibrariesScreenCallbacks
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        item(key = "explanation") {
            Text(
                text = model.explanation,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        }

        model.result?.let { result ->
            if (result.libraries.isNotEmpty()) {
                item(key = "result-heading") {
                    DetailsSectionHeading(
                        text = stringResource(R.string.detected_tracker_libraries_heading),
                        modifier = Modifier.padding(top = 28.dp)
                    )
                }
                item(key = "result-source") {
                    Text(
                        text = stringResource(R.string.tracker_libraries_source),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                itemsIndexed(
                    items = result.libraries,
                    key = { index, library -> "${library.name}:$index" }
                ) { index, library ->
                    LibraryListItem(library, callbacks::onWebsiteClick)
                    if (index < result.libraries.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .testTag("libraries-divider"),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                }
            } else if (result.rawText != null) {
                item(key = "raw-result") {
                    Text(
                        text = result.rawText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp)
                            .padding(top = 12.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            if (result.disclaimer != null) {
                item(key = "disclaimer") {
                    Text(
                        text = result.disclaimer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        model.progress?.let { progress ->
            item(key = "progress") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 28.dp)
                ) {
                    Text(
                        text = progress.text,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (progress.percent == null) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(top = 8.dp)
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { progress.percent.coerceIn(0, 100) / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(top = 8.dp)
                        )
                    }
                }
            }
        }

        item(key = "action") {
            Button(
                onClick = callbacks::onAnalyse,
                enabled = model.actionEnabled,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .padding(start = 16.dp, top = 24.dp, end = 16.dp, bottom = 32.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_scan_code),
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = model.actionText)
            }
        }
    }
}

@Composable
private fun LibraryListItem(
    library: LibraryRow,
    onWebsiteClick: (String) -> Unit
) {
    val website = library.website
    val websiteDescription = if (website != null) {
        stringResource(R.string.open_tracker_website, library.name)
    } else {
        null
    }
    val rowModifier = if (website != null) {
        Modifier
            .fillMaxWidth()
            .clickable { onWebsiteClick(website) }
            .semantics {
                contentDescription = websiteDescription!!
                role = Role.Button
            }
    } else {
        Modifier.fillMaxWidth()
    }
    ListItem(
        headlineContent = {
            Text(
                text = library.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (website != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        },
        trailingContent = if (website != null) {
            {
                Icon(
                    painter = painterResource(R.drawable.ic_open_in_new_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        } else {
            null
        },
        modifier = rowModifier
    )
}

@Preview(showBackground = true)
@Composable
private fun LibrariesScreenPreview() {
    TrackerControlTheme {
        LibrariesScreenContent(
            model = LibrariesScreenModel(
                explanation = "TrackerControl detects tracking in app code and network traffic.",
                result = LibrariesResult(
                    libraries = listOf(
                        LibraryRow("Example Analytics", "https://example.com"),
                        LibraryRow("Embedded Library", null)
                    ),
                    rawText = null,
                    disclaimer = "Detection does not mean that a library is actively used."
                ),
                progress = null,
                actionText = "Analyse tracker libraries",
                actionEnabled = true
            ),
            callbacks = object : LibrariesScreenCallbacks {
                override fun onAnalyse() = Unit
                override fun onWebsiteClick(website: String) = Unit
            }
        )
    }
}
