package net.kollnig.missioncontrol.details;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.compose.ui.platform.ComposeView;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.work.WorkInfo;

import com.google.android.material.snackbar.Snackbar;

import net.kollnig.missioncontrol.Common;
import net.kollnig.missioncontrol.DetailsActivity;
import net.kollnig.missioncontrol.R;
import net.kollnig.missioncontrol.analysis.TrackerAnalysisManager;
import net.kollnig.missioncontrol.data.AppProtectionState;
import net.kollnig.missioncontrol.data.BlockingMode;
import net.kollnig.missioncontrol.data.InternetBlocklist;
import net.kollnig.missioncontrol.data.PausedApps;
import net.kollnig.missioncontrol.data.RemoteRoutingLogic;
import net.kollnig.missioncontrol.data.Tracker;
import net.kollnig.missioncontrol.data.TrackerBlocklist;
import net.kollnig.missioncontrol.data.TrackerCategory;
import net.kollnig.missioncontrol.data.TrackerFeedLogic;
import net.kollnig.missioncontrol.data.TrackerList;
import net.kollnig.missioncontrol.data.TrackerStatusLogic;
import net.kollnig.missioncontrol.ui.compose.TrackersRow;
import net.kollnig.missioncontrol.ui.compose.TrackersScreen;
import net.kollnig.missioncontrol.ui.compose.TrackersScreenCallbacks;
import net.kollnig.missioncontrol.ui.compose.TrackersScreenController;
import net.kollnig.missioncontrol.ui.compose.TrackersScreenModel;

import eu.faircode.netguard.Rule;
import eu.faircode.netguard.Util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A fragment representing a list of Items.
 */
public class TrackersFragment extends Fragment {
    private static final String ARG_APP_ID = "app-id";
    private static final String ARG_APP_UID = "app-uid";
    private final String TAG = TrackersFragment.class.getSimpleName();
    private TrackerList trackerList;
    private String mAppId;
    private String mAppName;
    private int mAppUid;

    private ComposeView composeTrackers;
    private TrackersScreenController screenController;
    private List<TrackerCategory> categories = new ArrayList<>();
    private final Set<String> expandedCompanyKeys = new HashSet<>();
    private final Set<String> expandedSections = new HashSet<>();
    private final Map<String, Tracker> trackersByKey = new HashMap<>();
    @Nullable
    private WorkInfo analysisWork;
    /**
     * Cached result of the "can this app open a browser?" check used for the
     * warning banner. It can only change through package state the user alters
     * outside this screen, so it is invalidated on resume rather than paying
     * the PackageManager IPC on every render.
     */
    @Nullable
    private Boolean browserWarning;

    private boolean running = false;
    /** True while the async tracker query runs; drives the loading spinner. */
    private boolean refreshing = false;

    /**
     * Mandatory empty constructor for the fragment manager to instantiate the
     * fragment (e.g. upon screen orientation changes).
     */
    public TrackersFragment() {
    }

    public static TrackersFragment newInstance(String appId, int uid) {
        TrackersFragment fragment = new TrackersFragment();
        Bundle args = new Bundle();
        args.putString(ARG_APP_ID, appId);
        args.putInt(ARG_APP_UID, uid);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle bundle = getArguments();
        if (bundle != null) {
            mAppId = bundle.getString(ARG_APP_ID);
            mAppUid = bundle.getInt(ARG_APP_UID);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_trackers, container, false);

        running = true;

        Context c = v.getContext();
        trackerList = TrackerList.getInstance(c);
        mAppName = resolveAppName(c);
        composeTrackers = v.findViewById(R.id.composeTrackers);
        screenController = TrackersScreen.install(
                composeTrackers,
                buildScreenModel(),
                new TrackersScreenCallbacks() {
                    @Override
                    public void onAppStateClick() {
                        openProtection();
                    }

                    @Override
                    public void onLibrariesClick() {
                        openLibraries();
                    }

                    @Override
                    public void onSectionToggle(String categoryName, boolean checked) {
                        toggleSection(categoryName, checked);
                    }

                    @Override
                    public void onCompanyClick(String blockingKey) {
                        toggleCompanyExpansion(blockingKey);
                    }

                    @Override
                    public void onCompanyToggle(String blockingKey, boolean checked) {
                        toggleCompany(blockingKey, checked);
                    }

                    @Override
                    public void onShowMore(String categoryName) {
                        expandedSections.add(categoryName);
                        render();
                    }

                    @Override
                    public void onRefresh() {
                        updateTrackerList();
                    }
                });

        // Observe analysis work status and update the Compose model.
        setupAnalysisObserver();

        return v;
    }

    /**
     * Sets up WorkManager observer for tracker analysis, and starts an analysis
     * if this app has never been analysed or was updated since it last was.
     *
     * The analysis runs on opening this screen rather than in the background on
     * every install or update: it is the one moment the result is about to be
     * read, which keeps it off the battery budget the rest of the time. The
     * {@link TrackerAnalysisManager#shouldStartAnalysis} gate makes this at most
     * one run per app version, so re-opening the screen costs nothing.
     */
    private void setupAnalysisObserver() {
        TrackerAnalysisManager manager = TrackerAnalysisManager.getInstance(requireContext());

        // hasDeferredAnalysis covers the case shouldStartAnalysis cannot see: the
        // install broadcast already enqueued battery-deferred work and marked the
        // version attempted, so the gate says no while the screen would sit on a
        // spinner until the battery recovers. Requesting again upgrades it.
        if (manager.shouldStartAnalysis(mAppId) || manager.hasDeferredAnalysis(mAppId))
            manager.startAnalysis(mAppId);

        manager.getWorkInfoByPackageLiveData(mAppId).observe(getViewLifecycleOwner(), workInfoList -> {
            if (screenController == null)
                return;

            if (workInfoList.isEmpty()) {
                analysisWork = null;
                render();
                return;
            }

            // Find active (non-finished) work first
            WorkInfo activeWork = null;
            for (WorkInfo info : workInfoList) {
                if (!info.getState().isFinished()) {
                    activeWork = info;
                    break;
                }
            }

            // Fall back to most recent finished work
            if (activeWork == null) {
                activeWork = workInfoList.get(0);
            }

            analysisWork = activeWork;
            render();
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        browserWarning = null;
        updateTrackerList();
    }

    /**
     * Communicate with tracker database to show information about tracking in a
     * given app
     */
    public void updateTrackerList() {
        refreshing = true;
        render();
        new AsyncTask<Object, Object, List<TrackerCategory>>() {
            @Override
            protected List<TrackerCategory> doInBackground(Object... arg) {
                Context c = getContext();

                if (c == null)
                    return null;

                return trackerList.getAppTrackers(c, mAppUid);
            }

            @Override
            protected void onPostExecute(List<TrackerCategory> result) {
                refreshing = false;
                if (running && screenController != null) {
                    categories = result == null ? new ArrayList<>() : new ArrayList<>(result);
                    rebuildTrackerIndex();
                    render();

                    // no trackers yet found
                    if (result != null && result.size() == 0)
                        suggestLaunchingApp();
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /**
     * Remind user to start app to create some network traffic for analysis by
     * TrackerControl
     */
    private void suggestLaunchingApp() {
        Activity activity = getActivity();
        if (activity == null)
            return;

        // only suggest launching app if monitoring and internet access enabled
        SharedPreferences apply = activity.getSharedPreferences("apply", Context.MODE_PRIVATE);
        InternetBlocklist w = InternetBlocklist.getInstance(activity);
        if (!apply.getBoolean(mAppId, true)
                || w.blockedInternet(mAppUid))
            return;

        // retrieve app intent
        final Intent launch = Common.getLaunchIntent(activity, mAppId);
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        if (launch != null) {
            final boolean enabled = prefs.getBoolean("enabled", false);
            int msg = enabled ? R.string.no_trackers_found_message : R.string.no_trackers_found_message_disabled;
            Snackbar s = Common.getSnackbar(activity, msg);
            if (s == null)
                return;

            s.setAction(enabled ? R.string.no_trackers_found_action : R.string.back, v -> {
                if (enabled)
                    activity.startActivity(launch);
                else
                    activity.finish();
            }).show();
        }
    }

    private void render() {
        if (screenController != null)
            screenController.update(buildScreenModel());
    }

    private void rebuildTrackerIndex() {
        trackersByKey.clear();
        for (TrackerCategory category : categories) {
            for (Tracker tracker : category.getChildren())
                trackersByKey.put(TrackerBlocklist.getBlockingKey(tracker), tracker);
        }
    }

    private TrackersScreenModel buildScreenModel() {
        Context context = getContext();
        if (context == null)
            return new TrackersScreenModel(
                    false, "", null, "", false, "", new ArrayList<>(), refreshing);

        InternetBlocklist internet = InternetBlocklist.getInstance(context);
        TrackerBlocklist blocklist = TrackerBlocklist.getInstance(context);
        boolean internetBlocked = internet.blockedInternet(mAppUid);
        AppProtectionState state = currentState(context, internetBlocked);
        boolean trackerProtectionEnabled = BlockingMode.isTrackerProtectionEnabled(
                context, context.getSharedPreferences("tracker_protect", Context.MODE_PRIVATE), mAppId);
        boolean minimal = BlockingMode.isMinimalMode(context);
        boolean strict = BlockingMode.isStrictMode(context);
        String route = routeSummary();
        String appStateHint;
        boolean hintAccent;
        if (PausedApps.isPaused(context, mAppId)) {
            int remainingMinutes = PausedApps.getRemainingMinutes(context, mAppId);
            appStateHint = context.getResources().getQuantityString(
                    R.plurals.protection_paused_resumes, remainingMinutes, remainingMinutes);
            hintAccent = true;
        } else if (state == AppProtectionState.PROTECTED) {
            appStateHint = context.getString(BlockingMode.isMinimalMode(context)
                    ? R.string.protection_app_not_working : R.string.app_state_subtitle_misbehaving);
            hintAccent = false;
        } else {
            appStateHint = context.getString(R.string.protection_app_not_working);
            hintAccent = false;
        }

        // Category blocked state is looked up once per category per render (not
        // memoised across renders, since toggles change it), rather than being
        // re-queried per row that references the same category.
        Map<String, Boolean> categoryBlockedByName = new HashMap<>();
        for (TrackerCategory category : categories)
            categoryBlockedByName.put(category.getCategoryName(),
                    blocklist.blocked(mAppUid, category.getCategoryName()));

        List<TrackersRow> rows = new ArrayList<>();
        List<Object> feedRows = TrackerFeedLogic.buildRows(
                categories, expandedCompanyKeys, expandedSections);
        for (int index = 0; index < feedRows.size(); index++) {
            Object feedRow = feedRows.get(index);
            if (feedRow instanceof TrackerFeedLogic.SectionRow) {
                TrackerCategory category = ((TrackerFeedLogic.SectionRow) feedRow).getCategory();
                boolean categoryBlocked = categoryBlockedByName.get(category.getCategoryName());
                rows.add(buildSectionRow(context, state,
                        trackerProtectionEnabled, minimal, category, categoryBlocked));
            } else if (feedRow instanceof TrackerFeedLogic.CompanyRow) {
                TrackerFeedLogic.CompanyRow company = (TrackerFeedLogic.CompanyRow) feedRow;
                Object nextRow = index + 1 < feedRows.size() ? feedRows.get(index + 1) : null;
                boolean showDivider = nextRow instanceof TrackerFeedLogic.CompanyRow
                        || nextRow instanceof TrackerFeedLogic.ShowMoreRow;
                boolean categoryBlocked = categoryBlockedByName.get(company.getCategoryName());
                rows.add(buildCompanyRow(context, blocklist, state,
                        trackerProtectionEnabled, minimal, strict, internetBlocked,
                        company, showDivider, categoryBlocked));
            } else if (feedRow instanceof TrackerFeedLogic.ShowMoreRow) {
                TrackerFeedLogic.ShowMoreRow showMore = (TrackerFeedLogic.ShowMoreRow) feedRow;
                rows.add(new TrackersRow.ShowMore(
                        "more:" + showMore.getCategoryName(),
                        context.getResources().getQuantityString(
                                R.plurals.feed_show_more_companies,
                                showMore.getHiddenCount(), showMore.getHiddenCount()),
                        showMore.getCategoryName()));
            }
        }

        if (browserWarning == null) {
            Intent urlIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.wikipedia.org/"));
            urlIntent.setPackage(mAppId);
            browserWarning = Common.isCallable(context, urlIntent) && !Util.isPlayStoreInstall();
        }
        return new TrackersScreenModel(
                browserWarning,
                context.getString(state == AppProtectionState.PROTECTED
                        ? protectedTitleRes(context) : stateLabelRes(state)),
                route,
                appStateHint,
                hintAccent,
                librarySummary(context),
                Collections.unmodifiableList(rows),
                refreshing);
    }

    private TrackersRow.Section buildSectionRow(Context context,
            AppProtectionState state,
            boolean trackerProtectionEnabled,
            boolean minimal,
            TrackerCategory category,
            boolean categoryBlocked) {
        final String categoryName = category.getCategoryName();
        final String displayName = category.getDisplayName(context);
        boolean readOnlyMinimal = minimal || state == AppProtectionState.MINIMAL_ONLY;
        boolean categoryMinimallyBlocked = false;
        if (readOnlyMinimal) {
            for (Tracker tracker : category.getChildren()) {
                if (TrackerList.isMinimallyBlocked(tracker)) {
                    categoryMinimallyBlocked = true;
                    break;
                }
            }
        }
        boolean trackerPolicyActive = state == AppProtectionState.PROTECTED
                || state == AppProtectionState.MINIMAL_ONLY;
        boolean effectivelyBlocked = state == AppProtectionState.NO_INTERNET
                || (trackerProtectionEnabled && trackerPolicyActive
                && (readOnlyMinimal ? categoryMinimallyBlocked : categoryBlocked));
        String explainer = null;
        if (TrackerBlocklist.NECESSARY_CATEGORY.equals(categoryName)) {
            explainer = context.getString(effectivelyBlocked
                    ? R.string.feed_essential_explainer_blocked
                    : R.string.feed_essential_explainer, mAppName);
        }
        String lastContact = category.lastSeen != null && category.lastSeen != 0
                ? String.format(context.getString(R.string.feed_section_last_contact),
                Util.relativeTime(category.lastSeen)) : null;
        boolean checked = readOnlyMinimal
                ? trackerProtectionEnabled && categoryMinimallyBlocked : categoryBlocked;
        boolean enabled = !readOnlyMinimal && state == AppProtectionState.PROTECTED;
        return new TrackersRow.Section(
                "section:" + categoryName,
                categoryName,
                displayName,
                lastContact,
                explainer,
                checked,
                enabled,
                String.format(context.getString(R.string.toggle_block_category_description), displayName));
    }

    private TrackersRow.Company buildCompanyRow(Context context,
            TrackerBlocklist blocklist,
            AppProtectionState state,
            boolean trackerProtectionEnabled,
            boolean minimal,
            boolean strict,
            boolean internetBlocked,
            TrackerFeedLogic.CompanyRow row,
            boolean showDivider,
            boolean categoryBlocked) {
        Tracker tracker = row.getTracker();
        String categoryName = row.getCategoryName();
        String blockingKey = TrackerBlocklist.getBlockingKey(tracker);
        boolean companyKeyBlocked = blocklist.blocked(mAppUid, blockingKey);
        TrackerStatusLogic.Result status = TrackerStatusLogic.resolve(
                trackerProtectionEnabled,
                minimal,
                strict,
                state,
                TrackerList.isMinimallyBlocked(tracker),
                TrackerList.isMinimallyKnown(tracker),
                categoryBlocked,
                companyKeyBlocked,
                tracker.isAllowedInStandardMode());
        String companyName = tracker.getName();
        if (TrackerList.TRACKER_HOSTLIST.equals(companyName))
            companyName = context.getString(R.string.tracker_hostlist);
        String lastSeen = tracker.lastSeen != null && tracker.lastSeen != 0
                ? Util.relativeTime(tracker.lastSeen).toString() : null;
        String sharedIpNote = null;
        boolean showAllowSwitch = row.isExpanded();
        if (row.isExpanded()) {
            if (status.getStatus() == TrackerStatusLogic.Status.ALLOWED_SHARED_IP) {
                sharedIpNote = context.getString(R.string.allowed_shared_ip);
                showAllowSwitch = false;
            } else if (status.getStatus() == TrackerStatusLogic.Status.MONITORED) {
                sharedIpNote = context.getString(R.string.tracker_monitored_minimal);
            } else if (status.getStatus() == TrackerStatusLogic.Status.ALLOWED
                    && state == AppProtectionState.PROTECTED && !categoryBlocked) {
                sharedIpNote = context.getString(R.string.category_unblocked_warning);
            }
        }
        String displayName = tracker.getName();
        if (TrackerList.TRACKER_HOSTLIST.equals(displayName))
            displayName = context.getString(R.string.tracker_hostlist);
        boolean allowEnabled = status.getInteractivity() == TrackerStatusLogic.Interactivity.TOGGLEABLE
                && state == AppProtectionState.PROTECTED
                && categoryBlocked
                && !internetBlocked;
        String hosts = "";
        if (row.isExpanded()) {
            List<String> sortedHosts = new ArrayList<>(tracker.getHosts());
            Collections.sort(sortedHosts);
            hosts = TextUtils.join("\n", sortedHosts);
        }
        return new TrackersRow.Company(
                "company:" + categoryName + ":" + blockingKey,
                blockingKey,
                companyName,
                lastSeen,
                context.getString(statusString(status.getStatus())),
                status.getStatus() == TrackerStatusLogic.Status.BLOCKED,
                row.isExpanded(),
                hosts,
                row.isExpanded() && tracker.isUncertain(),
                showAllowSwitch,
                String.format(context.getString(R.string.feed_allow_company_in_app), displayName,
                        mAppName),
                status.getStatus() != TrackerStatusLogic.Status.BLOCKED,
                allowEnabled,
                sharedIpNote,
                showDivider,
                context.getString(R.string.feed_company_expanded),
                context.getString(R.string.feed_company_collapsed));
    }

    private void toggleSection(String categoryName, boolean checked) {
        Context context = getContext();
        if (context == null)
            return;
        TrackerBlocklist blocklist = TrackerBlocklist.getInstance(context);
        if (checked)
            blocklist.block(mAppUid, categoryName);
        else {
            blocklist.unblock(mAppUid, categoryName);
            Toast.makeText(context, R.string.category_unblocked, Toast.LENGTH_SHORT).show();
        }
        render();
    }

    private void toggleCompanyExpansion(String blockingKey) {
        if (expandedCompanyKeys.contains(blockingKey))
            expandedCompanyKeys.remove(blockingKey);
        else
            expandedCompanyKeys.add(blockingKey);
        render();
    }

    private void toggleCompany(String blockingKey, boolean checked) {
        Context context = getContext();
        Tracker tracker = trackersByKey.get(blockingKey);
        if (context == null || tracker == null)
            return;
        TrackerBlocklist blocklist = TrackerBlocklist.getInstance(context);
        if (checked)
            blocklist.unblock(mAppUid, tracker);
        else
            blocklist.block(mAppUid, tracker);
        render();
    }

    private void openProtection() {
        Context context = getContext();
        if (context == null)
            return;
        Intent intent = new Intent(context, ProtectionActivity.class);
        intent.putExtra(DetailsActivity.INTENT_EXTRA_APP_PACKAGENAME, mAppId);
        intent.putExtra(DetailsActivity.INTENT_EXTRA_APP_UID, mAppUid);
        intent.putExtra(DetailsActivity.INTENT_EXTRA_APP_NAME, mAppName);
        startActivity(intent);
    }

    private void openLibraries() {
        Context context = getContext();
        if (context == null)
            return;
        Intent intent = new Intent(context, LibrariesActivity.class);
        intent.putExtra(DetailsActivity.INTENT_EXTRA_APP_PACKAGENAME, mAppId);
        intent.putExtra(DetailsActivity.INTENT_EXTRA_APP_NAME, mAppName);
        startActivity(intent);
    }

    private int statusString(TrackerStatusLogic.Status status) {
        switch (status) {
            case BLOCKED:
                return R.string.timeline_tracker_blocked;
            case ALLOWED_BY_USER:
                return R.string.feed_allowed_by_you;
            case ALLOWED_SHARED_IP:
                return R.string.feed_allowed_shared_ip;
            case MONITORED:
                return R.string.feed_monitored;
            case ALLOWED:
            default:
                return R.string.timeline_tracker_allowed;
        }
    }

    private String librarySummary(Context context) {
        TrackerAnalysisManager manager = TrackerAnalysisManager.getInstance(context);
        String cached = manager.getCachedResult(mAppId);
        if (analysisWork != null && !analysisWork.getState().isFinished())
            return context.getString(R.string.libraries_analysing);
        if (cached == null) {
            if (analysisWork != null && analysisWork.getState() == WorkInfo.State.FAILED)
                return context.getString(R.string.libraries_analysis_failed);
            return context.getString(R.string.libraries_not_analysed);
        }
        int count = TrackerAnalysisManager.countTrackers(cached);
        String summary = count == 0 ? context.getString(R.string.libraries_none_found)
                : context.getResources().getQuantityString(R.plurals.libraries_found, count, count);
        if (manager.isCacheStale(mAppId))
            summary = context.getString(R.string.libraries_outdated_suffix, summary);
        return summary;
    }

    private String routeSummary() {
        Context context = getContext();
        if (context == null)
            return null;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences apply = context.getSharedPreferences("apply", Context.MODE_PRIVATE);
        boolean wgEnabled = prefs.getBoolean("wg_enabled", false)
                && !TextUtils.isEmpty(prefs.getString("wg_config", ""));
        boolean applyApp = apply.getBoolean(mAppId, true);
        boolean defaultRoutes = wgEnabled && RemoteRoutingHelper.hasDefaultRoutes(context, prefs);
        if (RemoteRoutingLogic.getUnavailableReason(wgEnabled, defaultRoutes, applyApp) != null)
            return null;
        String mode = RemoteRoutingLogic.normalizeMode(
                prefs.getString(Rule.PREF_WG_ROUTE_MODE, RemoteRoutingLogic.getDefaultMode()));
        boolean tunnelled = RemoteRoutingLogic.routesThroughTunnel(
                mode, RemoteRoutingHelper.getRouteOverride(context, mAppId), true);
        return context.getString(tunnelled ? R.string.app_route_through : R.string.app_route_direct);
    }

    private String resolveAppName(Context context) {
        try {
            return context.getPackageManager().getApplicationLabel(
                    context.getPackageManager().getApplicationInfo(mAppId, 0)).toString();
        } catch (PackageManager.NameNotFoundException ex) {
            return mAppId;
        }
    }

    private AppProtectionState currentState(Context context, boolean internetBlocked) {
        SharedPreferences apply = context.getSharedPreferences("apply", Context.MODE_PRIVATE);
        SharedPreferences trackerProtect = context.getSharedPreferences("tracker_protect", Context.MODE_PRIVATE);
        SharedPreferences minimalOnly = context.getSharedPreferences("tracker_essential", Context.MODE_PRIVATE);
        return AppProtectionState.resolve(
                apply.getBoolean(mAppId, true),
                BlockingMode.isTrackerProtectionEnabled(context, trackerProtect, mAppId),
                internetBlocked,
                BlockingMode.isMinimalOnlyApp(context, minimalOnly, mAppId));
    }

    private int protectedTitleRes(Context context) {
        if (BlockingMode.isMinimalMode(context))
            return R.string.app_state_title_minimal;
        if (BlockingMode.isStrictMode(context))
            return R.string.app_state_title_strict;
        return R.string.app_state_title_standard;
    }

    private static int stateLabelRes(AppProtectionState state) {
        switch (state) {
            case MINIMAL_ONLY:
                return R.string.app_state_essential_only;
            case TRACKERS_ALLOWED:
                return R.string.app_state_trackers_allowed;
            case NO_INTERNET:
                return R.string.app_state_no_internet;
            case BYPASSED:
                return R.string.app_state_bypassed;
            case PROTECTED:
            default:
                return R.string.app_state_protected;
        }
    }

    @Override
    public void onDestroyView() {
        composeTrackers = null;
        screenController = null;
        categories = new ArrayList<>();
        expandedCompanyKeys.clear();
        expandedSections.clear();
        analysisWork = null;
        trackersByKey.clear();

        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;
    }
}
