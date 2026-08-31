/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.kollnig.missioncontrol.ui.compose

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.text.format.DateUtils
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import net.kollnig.missioncontrol.R
import net.kollnig.missioncontrol.data.InsightsData
import net.kollnig.missioncontrol.data.TimelineEntry
import java.text.NumberFormat
import java.util.UUID

/** The four reasons an empty Timeline can be shown. */
enum class TimelineEmptyState {
    TRACKER_CONTROL_OFF,
    RECORDING_OFF,
    RECORDING_UNAVAILABLE,
    WATCHING
}

/** Immutable state for the Insights summary at the top of the Timeline. */
sealed interface TimelineInsightsState {
    data object Loading : TimelineInsightsState

    @Immutable
    data class Populated(val data: TimelineInsightsData) : TimelineInsightsState
}

@Immutable
data class TimelineInsightsData(
    val totalTrackingAttempts: Int,
    val blockedTrackingAttempts: Int,
    val allowedTrackingAttempts: Int,
    val uniqueTrackerCompanies: Int,
    val blockedPercentage: Int,
    val allowedPercentage: Int
) {
    companion object {
        @JvmStatic
        fun from(data: InsightsData): TimelineInsightsData = TimelineInsightsData(
            totalTrackingAttempts = data.totalTrackingAttempts,
            blockedTrackingAttempts = data.blockedTrackingAttempts,
            allowedTrackingAttempts = data.allowedTrackingAttempts,
            uniqueTrackerCompanies = data.uniqueTrackerCompanies,
            blockedPercentage = data.getBlockedPercentage(),
            allowedPercentage = 100 - data.getBlockedPercentage()
        )
    }
}

/** Immutable tracker-contact row shown below an app. */
@Immutable
data class TimelineTrackerContact(
    val key: String,
    val companyName: String,
    val category: String?,
    val blocked: Boolean,
    val statusLabel: String
) {
    val displayName: String
        get() = if (category.isNullOrBlank()) companyName else "$companyName · $category"
}

/** Stable-keyed rows consumed by the single Timeline LazyColumn. */
sealed interface TimelineRow {
    val key: String

    @Immutable
    data class Section(
        override val key: String,
        val title: String
    ) : TimelineRow

    @Immutable
    data class App(
        override val key: String,
        val sectionKey: String,
        val uid: Int,
        val appName: String,
        val packageName: String?,
        val relativeTime: String,
        val summary: String,
        val blockedCount: Int,
        val allowedCount: Int,
        val contacts: List<TimelineTrackerContact>,
        val overflowCount: Int
    ) : TimelineRow {
        val clickable: Boolean
            get() = packageName != null
    }
}

@Immutable
data class TimelineScreenModel(
    val insights: TimelineInsightsState,
    val emptyState: TimelineEmptyState?,
    val showHint: Boolean,
    val rows: List<TimelineRow>,
    val isRefreshing: Boolean = false
) {
    companion object {
        @JvmStatic
        fun initial(): TimelineScreenModel = TimelineScreenModel(
            insights = TimelineInsightsState.Loading,
            emptyState = null,
            showHint = false,
            rows = emptyList(),
            isRefreshing = false
        )
    }
}

/** Callbacks for actions whose implementation remains in TimelineFragment. */
interface TimelineScreenCallbacks {
    fun onEntryClick(uid: Int, appName: String, packageName: String)
    fun onOpenApp()
    fun onOpenSettings()
    fun onOpenInsights()
    fun onShareInsights()
    fun onDismissHint()

    /** Pull-to-refresh gesture; rebuilds the timeline and the insights card. */
    fun onRefresh()
}

/** Handle used by Java to update Compose while retaining controller lifecycle ownership. */
class TimelineScreenController internal constructor(
    private val state: MutableState<TimelineScreenModel>
) {
    @Volatile
    private var valid = true

    fun updateTimeline(
        entries: List<TimelineEntry>,
        context: Context,
        emptyState: TimelineEmptyState?,
        showHint: Boolean
    ) {
        if (!valid) return
        state.value = state.value.copy(
            emptyState = if (entries.isEmpty()) emptyState else null,
            showHint = showHint,
            rows = timelineRows(entries, context),
            // The rebuilt list is the completion signal for a pull-to-refresh,
            // as it was for the SwipeRefreshLayout this screen replaced.
            isRefreshing = false
        )
    }

    /** Show or hide the pull-to-refresh indicator. */
    fun setRefreshing(refreshing: Boolean) {
        if (!valid) return
        state.value = state.value.copy(isRefreshing = refreshing)
    }

    fun updateInsights(data: InsightsData) {
        if (!valid) return
        state.value = state.value.copy(
            insights = TimelineInsightsState.Populated(TimelineInsightsData.from(data))
        )
    }

    fun dismissHint() {
        if (!valid) return
        state.value = state.value.copy(showHint = false)
    }

    fun invalidate() {
        valid = false
    }
}

/** Java-facing entry point for the migrated Timeline screen. */
object TimelineScreen {
    @JvmStatic
    fun install(
        composeView: ComposeView,
        callbacks: TimelineScreenCallbacks
    ): TimelineScreenController = install(composeView, TimelineScreenModel.initial(), callbacks)

    @JvmStatic
    fun install(
        composeView: ComposeView,
        initialModel: TimelineScreenModel,
        callbacks: TimelineScreenCallbacks
    ): TimelineScreenController {
        val state = mutableStateOf(initialModel)
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        composeView.setContent {
            TrackerControlTheme {
                TimelineScreenContent(state.value, callbacks)
            }
        }
        return TimelineScreenController(state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimelineScreenContent(
    model: TimelineScreenModel,
    callbacks: TimelineScreenCallbacks
) {
    val iconCache = remember { mutableMapOf<String, Drawable?>() }
    CompositionLocalProvider(LocalAppIconCache provides iconCache) {
        PullToRefreshBox(
            isRefreshing = model.isRefreshing,
            onRefresh = callbacks::onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            TimelineList(model, callbacks)
        }
    }
}

@Composable
private fun TimelineList(
    model: TimelineScreenModel,
    callbacks: TimelineScreenCallbacks
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item(key = "insights-summary") {
            TimelineInsightsCard(model.insights, callbacks)
        }

        model.emptyState?.let { emptyState ->
            item(key = "timeline-empty") {
                TimelineEmptyContent(emptyState, callbacks)
            }
        }

        if (model.showHint) {
            item(key = "timeline-hint") {
                TimelineHintContent(callbacks)
            }
        }

        model.rows.forEachIndexed { index, row ->
            item(key = row.key) {
                when (row) {
                    is TimelineRow.Section -> DetailsSectionHeading(text = row.title)
                    is TimelineRow.App -> {
                        val next = model.rows.getOrNull(index + 1)
                        val showDivider = next is TimelineRow.App &&
                            next.sectionKey == row.sectionKey
                        TimelineAppRow(
                            row = row,
                            showDivider = showDivider,
                            onClick = {
                                row.packageName?.let { packageName ->
                                    callbacks.onEntryClick(row.uid, row.appName, packageName)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineInsightsCard(
    state: TimelineInsightsState,
    callbacks: TimelineScreenCallbacks
) {
    val insightsTitle = stringResource(R.string.insights_title)
    when (state) {
        TimelineInsightsState.Loading -> Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { contentDescription = insightsTitle },
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(104.dp)
                    .testTag("timeline-insights-loading"),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }

        is TimelineInsightsState.Populated -> {
            val data = state.data
            val locale = LocalLocale.current.platformLocale
            val numberFormat = NumberFormat.getNumberInstance(locale)
            val percentFormat = NumberFormat.getPercentInstance(locale).apply {
                maximumFractionDigits = 0
            }
            val totalText = numberFormat.format(data.totalTrackingAttempts)
            val blockedText = numberFormat.format(data.blockedTrackingAttempts)
            val allowedText = numberFormat.format(data.allowedTrackingAttempts)
            val blockedPercentageText = percentFormat.format(data.blockedPercentage / 100f)
            val seeMoreLabel = stringResource(R.string.insights_see_more)
            val shareLabel = stringResource(R.string.insights_share)
            val totalDescription = stringResource(
                R.string.accessibility_stat_description,
                totalText,
                stringResource(R.string.insights_tracking_attempts)
            )
            val companiesText = numberFormat.format(data.uniqueTrackerCompanies)
            val companiesDescription = stringResource(
                R.string.accessibility_stat_description,
                companiesText,
                stringResource(R.string.insights_tracker_companies)
            )
            val percentageDescription = stringResource(
                R.string.accessibility_stat_description,
                blockedPercentageText,
                stringResource(R.string.insights_blocked)
            )
            val statusText = stringResource(
                R.string.timeline_insights_status,
                blockedText,
                stringResource(R.string.insights_blocked),
                allowedText,
                stringResource(R.string.insights_allowed)
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clickable(
                        role = Role.Button,
                        onClick = callbacks::onOpenInsights
                    )
                    .testTag("timeline-insights-card"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_shield_check),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = stringResource(R.string.insights_subtitle_7days),
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        // No size() here: IconButton's own 48dp minimum touch
                        // target must survive; the glyph is sized on the Icon.
                        IconButton(
                            onClick = callbacks::onShareInsights,
                            modifier = Modifier.semantics { contentDescription = shareLabel }
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_ios_share),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .semantics(mergeDescendants = true) {
                                    contentDescription = totalDescription
                                }
                        ) {
                            Text(
                                text = totalText,
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.insights_tracking_attempts),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        // Middle stat of the pre-Compose hero row: how many
                        // distinct tracking companies the week's contacts came
                        // from, between the attempt count and the blocked share.
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .semantics(mergeDescendants = true) {
                                    contentDescription = companiesDescription
                                }
                        ) {
                            Text(
                                text = companiesText,
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.insights_tracker_companies),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Column(
                            modifier = Modifier.semantics(mergeDescendants = true) {
                                contentDescription = percentageDescription
                            },
                            horizontalAlignment = Alignment.End
                        ) {
                            Text(
                                text = blockedPercentageText,
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.insights_blocked),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    TimelineProgressBar(
                        progress = data.blockedPercentage / 100f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .height(6.dp)
                            .testTag("timeline-insights-progress"),
                        indicatorColor = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surface
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = statusText,
                            modifier = Modifier
                                .weight(1f)
                                .semantics { contentDescription = statusText },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            text = seeMoreLabel,
                            modifier = Modifier.padding(start = 12.dp),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineProgressBar(
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
            .clip(RoundedCornerShape(3.dp))
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
private fun TimelineEmptyContent(
    state: TimelineEmptyState,
    callbacks: TimelineScreenCallbacks
) {
    val (title, subtitle) = when (state) {
        TimelineEmptyState.TRACKER_CONTROL_OFF ->
            stringResource(R.string.timeline_empty_disabled_title) to
                stringResource(R.string.timeline_empty_disabled_subtitle)
        TimelineEmptyState.RECORDING_OFF ->
            stringResource(R.string.timeline_empty_recording_off_title) to
                stringResource(R.string.timeline_empty_recording_off_subtitle)
        TimelineEmptyState.RECORDING_UNAVAILABLE ->
            stringResource(R.string.timeline_empty_recording_unavailable_title) to
                stringResource(R.string.timeline_empty_recording_unavailable_subtitle)
        TimelineEmptyState.WATCHING ->
            stringResource(R.string.timeline_empty_enabled_title) to
                stringResource(R.string.timeline_empty_enabled_subtitle)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 28.dp)
            .semantics { contentDescription = "$title. $subtitle" },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() }
        )
        Text(
            text = subtitle,
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        when (state) {
            TimelineEmptyState.RECORDING_OFF -> Button(
                onClick = callbacks::onOpenSettings,
                modifier = Modifier
                    .padding(top = 20.dp)
                    .testTag("timeline-empty-open-settings")
            ) {
                Text(stringResource(R.string.timeline_open_settings))
            }
            TimelineEmptyState.WATCHING -> Button(
                onClick = callbacks::onOpenApp,
                modifier = Modifier
                    .padding(top = 20.dp)
                    .testTag("timeline-empty-open-app")
            ) {
                Text(stringResource(R.string.timeline_open_app))
            }
            else -> Unit
        }
    }
}

@Composable
private fun TimelineHintContent(callbacks: TimelineScreenCallbacks) {
    val hintText = stringResource(R.string.timeline_hint_tap_entry)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .semantics { contentDescription = hintText },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.timeline_hint_tap_entry),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            OutlinedButton(
                onClick = callbacks::onDismissHint,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 8.dp)
                    .testTag("timeline-hint-dismiss")
            ) {
                Text(stringResource(R.string.timeline_hint_dismiss))
            }
        }
    }
}

@Composable
private fun TimelineAppRow(
    row: TimelineRow.App,
    showDivider: Boolean,
    onClick: () -> Unit
) {
    val rowModifier = Modifier
        .fillMaxWidth()
        .then(
            if (row.clickable) {
                Modifier.clickable(role = Role.Button, onClick = onClick)
            } else {
                Modifier
            }
        )
        .semantics(mergeDescendants = true) {
            if (row.clickable) role = Role.Button
        }

    Column(
        modifier = rowModifier
            .testTag("timeline-app-${row.uid}")
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TimelineAppIcon(row.packageName)
            Text(
                text = row.appName,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = row.relativeTime,
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = row.summary,
            modifier = Modifier.padding(start = 48.dp, top = 2.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(
            modifier = Modifier.padding(start = 48.dp, top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            row.contacts.forEach { contact ->
                TimelineTrackerRow(contact)
            }
            if (row.overflowCount > 0) {
                Text(
                    text = stringResource(R.string.timeline_more_trackers, row.overflowCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier
                    .padding(start = 48.dp, top = 12.dp)
                    .testTag("timeline-divider"),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

@Composable
private fun TimelineTrackerRow(contact: TimelineTrackerContact) {
    val statusColour = if (contact.blocked) {
        colorResource(R.color.timeline_blocked)
    } else {
        colorResource(R.color.timeline_allowed)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = contact.statusLabel,
            modifier = Modifier.padding(end = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = statusColour,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = contact.displayName,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Per-screen icon cache. The Timeline re-renders every 30 seconds, and without
 * this every visible row would hit the PackageManager again on each tick.
 * Scoped to the composition (rather than a process-wide map) so the drawables
 * are released with the screen.
 */
private val LocalAppIconCache = staticCompositionLocalOf<MutableMap<String, Drawable?>> {
    mutableMapOf()
}

@Composable
private fun TimelineAppIcon(packageName: String?) {
    val iconCache = LocalAppIconCache.current
    AndroidView(
        factory = { viewContext ->
            ImageView(viewContext).apply {
                importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
        },
        update = { imageView ->
            val icon = packageName?.let { name ->
                if (iconCache.containsKey(name)) {
                    iconCache[name]
                } else {
                    findAppIcon(imageView.context, name).also { iconCache[name] = it }
                }
            }
            if (icon != null) {
                imageView.setImageDrawable(icon)
            } else {
                imageView.setImageResource(android.R.drawable.sym_def_app_icon)
            }
        },
        modifier = Modifier.size(36.dp)
    )
}

private fun findAppIcon(context: Context, packageName: String): Drawable? = try {
    val applicationInfo = context.packageManager.getApplicationInfo(packageName, 0)
    context.packageManager.getApplicationIcon(applicationInfo)
} catch (_: PackageManager.NameNotFoundException) {
    null
}

private fun timelineRows(entries: List<TimelineEntry>, context: Context): List<TimelineRow> {
    if (entries.isEmpty()) return emptyList()

    val now = System.currentTimeMillis()
    val oneHourAgo = now - 60 * 60 * 1000L
    val startOfToday = startOfDay(0)
    val startOfYesterday = startOfDay(1)
    val rows = mutableListOf<TimelineRow>()
    var currentSection: String? = null
    var currentSectionKey: String? = null

    entries.forEach { entry ->
        val sectionTitle = when {
            entry.mostRecentTime >= oneHourAgo -> context.getString(R.string.timeline_section_last_hour)
            entry.mostRecentTime >= startOfToday -> context.getString(R.string.timeline_section_today)
            entry.mostRecentTime >= startOfYesterday -> context.getString(R.string.timeline_section_yesterday)
            else -> context.getString(R.string.timeline_section_this_week)
        }
        if (sectionTitle != currentSection) {
            currentSection = sectionTitle
            currentSectionKey = stableKey("section:$sectionTitle")
            rows += TimelineRow.Section(currentSectionKey!!, sectionTitle)
        }

        val blockedCount = entry.getBlockedCount()
        val allowedCount = entry.getAllowedCount()
        val summary = when {
            blockedCount > 0 && allowedCount > 0 ->
                context.getString(R.string.timeline_summary_mixed, blockedCount, allowedCount)
            blockedCount > 0 ->
                context.resources.getQuantityString(
                    R.plurals.timeline_trackers_blocked,
                    blockedCount,
                    blockedCount
                )
            else ->
                context.resources.getQuantityString(
                    R.plurals.timeline_trackers_allowed,
                    allowedCount,
                    allowedCount
                )
        }
        val contacts = entry.trackers.take(3).map { tracker ->
            val statusLabel = context.getString(
                if (tracker.blocked) R.string.timeline_tracker_blocked
                else R.string.timeline_tracker_allowed
            )
            TimelineTrackerContact(
                key = stableKey("contact:${entry.uid}:${tracker.companyName}:${tracker.blocked}"),
                companyName = tracker.companyName,
                category = tracker.category,
                blocked = tracker.blocked,
                statusLabel = statusLabel
            )
        }
        val relativeTime = DateUtils.getRelativeTimeSpanString(
            entry.mostRecentTime,
            now,
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        ).toString()
        rows += TimelineRow.App(
            key = stableKey("entry:${entry.uid}"),
            sectionKey = currentSectionKey!!,
            uid = entry.uid,
            appName = entry.appName,
            packageName = entry.packageName,
            relativeTime = relativeTime,
            summary = summary,
            blockedCount = blockedCount,
            allowedCount = allowedCount,
            contacts = contacts,
            overflowCount = (entry.trackers.size - 3).coerceAtLeast(0)
        )
    }
    return rows
}

private fun startOfDay(daysAgo: Int): Long {
    val calendar = java.util.Calendar.getInstance()
    calendar.add(java.util.Calendar.DAY_OF_YEAR, -daysAgo)
    calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
    calendar.set(java.util.Calendar.MINUTE, 0)
    calendar.set(java.util.Calendar.SECOND, 0)
    calendar.set(java.util.Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}

private fun stableKey(identity: String): String =
    UUID.nameUUIDFromBytes(identity.toByteArray(Charsets.UTF_8)).toString()

@Preview(showBackground = true)
@Composable
private fun TimelineScreenPreview() {
    TrackerControlTheme {
        TimelineScreenContent(
            model = TimelineScreenModel(
                insights = TimelineInsightsState.Populated(
                    TimelineInsightsData(12, 9, 3, 4, 75, 25)
                ),
                emptyState = null,
                showHint = true,
                rows = listOf(
                    TimelineRow.Section("section-preview", "Today"),
                    TimelineRow.App(
                        key = "app-preview",
                        sectionKey = "section-preview",
                        uid = 10001,
                        appName = "Example app",
                        packageName = "com.example.app",
                        relativeTime = "3 min ago",
                        summary = "2 trackers blocked · 1 allowed",
                        blockedCount = 2,
                        allowedCount = 1,
                        contacts = listOf(
                            TimelineTrackerContact(
                                key = "contact-preview",
                                companyName = "Example Analytics",
                                category = "Advertising",
                                blocked = true,
                                statusLabel = "Blocked"
                            )
                        ),
                        overflowCount = 0
                    )
                )
            ),
            callbacks = PreviewTimelineCallbacks
        )
    }
}

private object PreviewTimelineCallbacks : TimelineScreenCallbacks {
    override fun onEntryClick(uid: Int, appName: String, packageName: String) = Unit
    override fun onOpenApp() = Unit
    override fun onOpenSettings() = Unit
    override fun onOpenInsights() = Unit
    override fun onShareInsights() = Unit
    override fun onDismissHint() = Unit
    override fun onRefresh() = Unit
}
