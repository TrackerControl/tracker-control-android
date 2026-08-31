/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.kollnig.missioncontrol.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLocale
import net.kollnig.missioncontrol.R
import net.kollnig.missioncontrol.data.InsightsData
import java.text.NumberFormat

/** One immutable row in a pervasive-tracker or domain summary. */
@Immutable
data class InsightsListItem(
    val name: String,
    val count: Int
)

/** Immutable data required to render populated Insights content. */
@Immutable
data class InsightsScreenData(
    val totalTrackingAttempts: Int,
    val blockedTrackingAttempts: Int,
    val allowedTrackingAttempts: Int,
    val uniqueTrackerCompanies: Int,
    val appsWithTrackers: Int,
    val blockedPercentage: Int,
    val allowedPercentage: Int,
    val pervasiveTrackers: List<InsightsListItem>,
    val topDomains: List<InsightsListItem>
)

/** The three presentation states owned by the immutable screen model. */
sealed interface InsightsUiState {
    data object Loading : InsightsUiState
    data object Empty : InsightsUiState
    data class Populated(val data: InsightsScreenData) : InsightsUiState
}

/** Immutable presentation model; loading and share actions stay in the Activity. */
@Immutable
data class InsightsScreenModel(
    val state: InsightsUiState
) {
    companion object {
        @JvmStatic
        fun loading(): InsightsScreenModel = InsightsScreenModel(InsightsUiState.Loading)

        @JvmStatic
        fun from(data: InsightsData): InsightsScreenModel {
            if (!data.hasData()) {
                return InsightsScreenModel(InsightsUiState.Empty)
            }

            return InsightsScreenModel(
                InsightsUiState.Populated(
                    InsightsScreenData(
                        totalTrackingAttempts = data.totalTrackingAttempts,
                        blockedTrackingAttempts = data.blockedTrackingAttempts,
                        allowedTrackingAttempts = data.allowedTrackingAttempts,
                        uniqueTrackerCompanies = data.uniqueTrackerCompanies,
                        appsWithTrackers = data.appsWithTrackers,
                        blockedPercentage = data.getBlockedPercentage(),
                        allowedPercentage = 100 - data.getBlockedPercentage(),
                        pervasiveTrackers = data.pervasiveTrackers.map {
                            InsightsListItem(it.first, it.second)
                        }.toList(),
                        topDomains = data.topDomains.map {
                            InsightsListItem(it.first, it.second)
                        }.toList()
                    )
                )
            )
        }
    }
}

/** Callback boundary for actions whose implementation remains in InsightsActivity. */
interface InsightsScreenCallbacks {
    fun onShare()
}

/** Handle used by the Activity to update Compose without moving loading state or lifecycle logic. */
class InsightsScreenController internal constructor(
    private val state: MutableState<InsightsScreenModel>
) {
    fun update(model: InsightsScreenModel) {
        state.value = model
    }
}

/** Java/Kotlin-facing entry point for the incrementally migrated Insights screen. */
object InsightsScreen {
    @JvmStatic
    fun install(
        composeView: ComposeView,
        initialModel: InsightsScreenModel,
        callbacks: InsightsScreenCallbacks
    ): InsightsScreenController {
        val state = mutableStateOf(initialModel)
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        composeView.setContent {
            TrackerControlTheme {
                InsightsScreenContent(state.value, callbacks)
            }
        }
        return InsightsScreenController(state)
    }
}

@Composable
internal fun InsightsScreenContent(
    model: InsightsScreenModel,
    callbacks: InsightsScreenCallbacks
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when (val state = model.state) {
            InsightsUiState.Loading -> LoadingContent()
            InsightsUiState.Empty -> EmptyContent()
            is InsightsUiState.Populated -> PopulatedContent(state.data, callbacks)
        }
    }
}

@Composable
private fun LoadingContent() {
    Box(
        modifier = Modifier
            .fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.testTag("insights-loading"))
    }
}

@Composable
private fun EmptyContent() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_shield_off),
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.insights_no_data),
            modifier = Modifier.padding(top = 16.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun PopulatedContent(
    data: InsightsScreenData,
    callbacks: InsightsScreenCallbacks
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item(key = "overview") {
            InsightsOverviewCard(
                data = data,
                onShare = callbacks::onShare,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        item(key = "reach") {
            InsightsReachCard(
                data = data,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        item(key = "pervasive-heading") {
            DetailsSectionHeading(text = stringResource(R.string.insights_pervasive_trackers))
        }
        item(key = "pervasive-list") {
            InsightsListCard(
                items = data.pervasiveTrackers,
                showAsAppCount = true,
                progressColor = MaterialTheme.colorScheme.primary,
                progressTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        item(key = "domains-heading") {
            DetailsSectionHeading(text = stringResource(R.string.insights_top_domains))
        }
        item(key = "domains-list") {
            InsightsListCard(
                items = data.topDomains,
                showAsAppCount = true,
                progressColor = MaterialTheme.colorScheme.primary,
                progressTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        item(key = "bottom-spacer") {
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun InsightsOverviewCard(
    data: InsightsScreenData,
    onShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusText = stringResource(
        R.string.insights_overview_status,
        formatNumber(data.blockedTrackingAttempts),
        stringResource(R.string.insights_blocked),
        formatNumber(data.allowedTrackingAttempts),
        stringResource(R.string.insights_allowed)
    )
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("insights-hero"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_shield_check),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.insights_subtitle_7days),
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(
                    onClick = onShare,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("insights-share")
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_ios_share),
                        contentDescription = stringResource(R.string.insights_share),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = formatNumber(data.totalTrackingAttempts),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.insights_tracking_attempts),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = formatPercentage(data.blockedPercentage),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.insights_blocked),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            InsightsProgressBar(
                progress = data.blockedPercentage / 100f,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .height(6.dp)
                    .testTag("insights-blocked-progress"),
                indicatorColor = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surface
            )
            Text(
                text = statusText,
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InsightsReachCard(
    data: InsightsScreenData,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("insights-reach-card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            InsightReachMetric(
                modifier = Modifier.weight(1f),
                icon = R.drawable.ic_domain,
                count = data.uniqueTrackerCompanies,
                label = stringResource(R.string.insights_tracker_companies)
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(56.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            InsightReachMetric(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 20.dp),
                icon = R.drawable.ic_apps,
                count = data.appsWithTrackers,
                label = stringResource(R.string.insights_apps_with_trackers)
            )
        }
    }
}

@Composable
private fun InsightReachMetric(
    modifier: Modifier,
    icon: Int,
    count: Int,
    label: String
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(
                text = formatNumber(count),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InsightsListCard(
    items: List<InsightsListItem>,
    showAsAppCount: Boolean,
    progressColor: Color,
    progressTrackColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        if (items.isEmpty()) {
            Text(
                text = stringResource(R.string.none),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val maxCount = items.maxOf { it.count }.coerceAtLeast(1)
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                items.forEachIndexed { index, item ->
                    InsightListRow(
                        item = item,
                        maxCount = maxCount,
                        showAsAppCount = showAsAppCount,
                        progressColor = progressColor,
                        progressTrackColor = progressTrackColor
                    )
                    if (index < items.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InsightListRow(
    item: InsightsListItem,
    maxCount: Int,
    showAsAppCount: Boolean,
    progressColor: Color,
    progressTrackColor: Color
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = item.name,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 1,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            InsightsProgressBar(
                progress = item.count.toFloat() / maxCount,
                modifier = Modifier
                    .weight(1f)
                    .height(8.dp),
                indicatorColor = progressColor,
                trackColor = progressTrackColor
            )
            Text(
                text = if (showAsAppCount) {
                    pluralStringResource(R.plurals.insights_apps_count, item.count, item.count)
                } else {
                    formatNumber(item.count)
                },
                modifier = Modifier.padding(start = 12.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InsightsProgressBar(
    progress: Float,
    indicatorColor: Color,
    trackColor: Color,
    modifier: Modifier = Modifier
) {
    val clampedProgress = progress.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(clampedProgress, 0f..1f)
            }
            .clip(RoundedCornerShape(4.dp))
            .background(trackColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(clampedProgress)
                .fillMaxHeight()
                .background(indicatorColor)
        )
    }
}

@Composable
private fun formatNumber(value: Int): String =
    NumberFormat.getNumberInstance(LocalLocale.current.platformLocale).format(value)

@Composable
private fun formatPercentage(value: Int): String =
    NumberFormat.getPercentInstance(LocalLocale.current.platformLocale).apply {
        maximumFractionDigits = 0
    }.format(value / 100f)

@Preview(showBackground = true)
@Composable
private fun InsightsScreenPreview() {
    TrackerControlTheme {
        InsightsScreenContent(
            model = InsightsScreenModel(
                InsightsUiState.Populated(
                    InsightsScreenData(
                        totalTrackingAttempts = 1_247,
                        blockedTrackingAttempts = 891,
                        allowedTrackingAttempts = 356,
                        uniqueTrackerCompanies = 34,
                        appsWithTrackers = 12,
                        blockedPercentage = 71,
                        allowedPercentage = 29,
                        pervasiveTrackers = listOf(
                            InsightsListItem("Example Analytics", 9),
                            InsightsListItem("Social Metrics", 5),
                            InsightsListItem("Ad Services", 3)
                        ),
                        topDomains = listOf(
                            InsightsListItem("metrics.example.com", 8),
                            InsightsListItem("ads.example.net", 5),
                            InsightsListItem("telemetry.example.org", 3)
                        )
                    )
                )
            ),
            callbacks = object : InsightsScreenCallbacks {
                override fun onShare() = Unit
            }
        )
    }
}
