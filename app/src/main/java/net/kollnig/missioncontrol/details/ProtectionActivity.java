/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package net.kollnig.missioncontrol.details;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.MenuItem;

import androidx.appcompat.app.AppCompatActivity;
import androidx.compose.ui.platform.ComposeView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.MaterialToolbar;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import eu.faircode.netguard.Rule;
import eu.faircode.netguard.ServiceSinkhole;
import eu.faircode.netguard.Util;
import net.kollnig.missioncontrol.DetailsActivity;
import net.kollnig.missioncontrol.R;
import net.kollnig.missioncontrol.data.AppProtectionState;
import net.kollnig.missioncontrol.data.AppProtectionWriter;
import net.kollnig.missioncontrol.data.BlockingMode;
import net.kollnig.missioncontrol.data.InternetBlocklist;
import net.kollnig.missioncontrol.data.PausedApps;
import net.kollnig.missioncontrol.data.RemoteRoutingLogic;
import net.kollnig.missioncontrol.data.Tracker;
import net.kollnig.missioncontrol.data.TrackerBlocklist;
import net.kollnig.missioncontrol.data.TrackerCategory;
import net.kollnig.missioncontrol.data.TrackerList;
import net.kollnig.missioncontrol.data.TrackerStatusLogic;
import net.kollnig.missioncontrol.ui.compose.BlockedState;
import net.kollnig.missioncontrol.ui.compose.CategoryModel;
import net.kollnig.missioncontrol.ui.compose.CompanyModel;
import net.kollnig.missioncontrol.ui.compose.PauseSection;
import net.kollnig.missioncontrol.ui.compose.ProtectionScreen;
import net.kollnig.missioncontrol.ui.compose.ProtectionScreenCallbacks;
import net.kollnig.missioncontrol.ui.compose.ProtectionScreenController;
import net.kollnig.missioncontrol.ui.compose.ProtectionScreenModel;
import net.kollnig.missioncontrol.ui.compose.RouteModel;
import net.kollnig.missioncontrol.ui.compose.RouteOption;
import net.kollnig.missioncontrol.ui.compose.StateOption;
import androidx.preference.PreferenceManager;

/** Full-screen per-app protection and temporary pause controls. */
public class ProtectionActivity extends AppCompatActivity {
    private String appPackageName;
    private int appUid;
    private String appName;

    private ComposeView composeProtection;
    private ProtectionScreenController screenController;
    private List<TrackerCategory> blockedTrackerCategories = new ArrayList<>();
    private boolean blockedTrackersLoaded;
    private boolean blockedRecordingUnavailable;
    private int blockedTrackerLoadGeneration;
    private boolean activityDestroyed;
    /** What the pause button currently offers, so a tap never contradicts its label. */
    private boolean showingResume;
    private final Handler expiryHandler = new Handler(Looper.getMainLooper());
    private final Runnable expiryRunnable = this::onPauseExpired;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_protection);

        Intent intent = getIntent();
        appPackageName = intent.getStringExtra(DetailsActivity.INTENT_EXTRA_APP_PACKAGENAME);
        appUid = intent.getIntExtra(DetailsActivity.INTENT_EXTRA_APP_UID, -1);
        appName = intent.getStringExtra(DetailsActivity.INTENT_EXTRA_APP_NAME);
        if (TextUtils.isEmpty(appPackageName) || appUid < 0) {
            finish();
            return;
        }
        if (TextUtils.isEmpty(appName))
            appName = appPackageName;

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }
        toolbar.setTitle(R.string.protection_title);
        toolbar.setSubtitle(appName);

        composeProtection = findViewById(R.id.composeProtection);
        screenController = ProtectionScreen.install(
                composeProtection,
                buildScreenModel(),
                new ProtectionScreenCallbacks() {
                    @Override
                    public void onPauseResume() {
                        pauseOrResume();
                    }

                    @Override
                    public void onStateSelected(AppProtectionState value) {
                        AppProtectionWriter.applyManual(ProtectionActivity.this,
                                appPackageName, appUid, AppProtectionState.of(value));
                        updateProtectionState();
                    }

                    @Override
                    public void onRouteSelected(boolean tunnelled) {
                        applyRoute(tunnelled);
                    }

                    @Override
                    public void onCategoryToggle(String categoryKey, boolean checked) {
                        toggleCategory(categoryKey, checked);
                    }

                    @Override
                    public void onCompanyClick(String blockingKey) {
                        toggleCompany(blockingKey);
                    }
                });

        AppBarLayout appBar = findViewById(R.id.appbar);
        final int appBarInitialTop = appBar.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(appBar, (v, insets) -> {
            Insets sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), appBarInitialTop + sysBars.top,
                    v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });

        final int composeInitialBottom = composeProtection.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(composeProtection, (v, insets) -> {
            Insets sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                    composeInitialBottom + sysBars.bottom);
            return insets;
        });

        updateProtectionState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (screenController != null) {
            updateProtectionState();
            loadBlockedTrackers();
        }
    }

    @Override
    protected void onDestroy() {
        activityDestroyed = true;
        blockedTrackerLoadGeneration++;
        screenController = null;
        composeProtection = null;
        super.onDestroy();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void updateProtectionState() {
        boolean paused = PausedApps.isPaused(this, appPackageName);
        // The pause ends on its own. Rebind once when it does, so a screen left
        // open never shows a countdown that has run out. One message, armed only
        // while this screen is in the foreground, so nothing polls.
        expiryHandler.removeCallbacks(expiryRunnable);
        showingResume = paused;
        if (paused) {
            expiryHandler.postDelayed(expiryRunnable,
                    PausedApps.remainingMillis(this, appPackageName) + 1_000L);
        }
        render();
    }

    /** Builds the complete immutable body model from the current preferences. */
    private ProtectionScreenModel buildScreenModel() {
        AppProtectionState state = currentState();
        boolean paused = PausedApps.isPaused(this, appPackageName);
        showingResume = paused;
        List<String> sharedPackages = PausedApps.getSharedUidPackages(this, appPackageName, appUid);

        PauseSection pause = null;
        boolean pauseAvailable = paused || state == AppProtectionState.PROTECTED
                || state == AppProtectionState.MINIMAL_ONLY
                || state == AppProtectionState.TRACKERS_ALLOWED;
        if (pauseAvailable) {
            String status;
            String action;
            if (paused) {
                int remainingMinutes = PausedApps.getRemainingMinutes(this, appPackageName);
                status = getResources().getQuantityString(
                        R.plurals.protection_paused_resumes, remainingMinutes, remainingMinutes);
                action = getString(R.string.protection_resume);
            } else {
                status = stateLabel(state);
                int pauseMinutes = PausedApps.getConfiguredDurationMinutes(this);
                action = getResources().getQuantityString(R.plurals.pause,
                        pauseMinutes, pauseMinutes);
            }
            String sharedUidText = sharedPackages.isEmpty() ? null
                    : getString(R.string.protection_pause_shared_uid, packageLabels(sharedPackages));
            String lockdownText = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? getString(R.string.protection_lockdown_note) : null;
            pause = new PauseSection(status, sharedUidText, action, lockdownText);
        }

        List<StateOption> stateOptions = buildStateOptions(state, paused, sharedPackages);
        BlockedState blocked = buildBlockedState(state);
        return new ProtectionScreenModel(pause, blocked, stateOptions, buildRouteModel());
    }

    private List<StateOption> buildStateOptions(AppProtectionState state, boolean paused,
            List<String> sharedPackages) {
        AppProtectionState shownState = paused ? AppProtectionState.BYPASSED : state;
        String noInternetExplanation = getString(R.string.app_state_no_internet_explanation);
        if (!sharedPackages.isEmpty())
            noInternetExplanation = getString(R.string.app_state_no_internet_explanation_shared,
                    noInternetExplanation,
                    getString(R.string.app_state_no_internet_shared_uid, packageLabels(sharedPackages)));

        boolean showMinimal = !BlockingMode.isMinimalMode(this) && !Util.isPlayStoreInstall(this);
        List<StateOption> options = new ArrayList<>();
        options.add(new StateOption(AppProtectionState.PROTECTED,
                getString(R.string.app_state_protected),
                getString(R.string.app_state_protected_explanation),
                shownState == AppProtectionState.PROTECTED, true));
        options.add(new StateOption(AppProtectionState.MINIMAL_ONLY,
                getString(R.string.app_state_essential_only),
                getString(R.string.app_state_essential_only_explanation),
                shownState == AppProtectionState.MINIMAL_ONLY, showMinimal));
        options.add(new StateOption(AppProtectionState.TRACKERS_ALLOWED,
                getString(R.string.app_state_trackers_allowed),
                getString(R.string.app_state_trackers_allowed_explanation),
                shownState == AppProtectionState.TRACKERS_ALLOWED, true));
        options.add(new StateOption(AppProtectionState.NO_INTERNET,
                getString(R.string.app_state_no_internet), noInternetExplanation,
                shownState == AppProtectionState.NO_INTERNET, true));
        String bypassedTitle = getString(R.string.app_state_bypassed);
        if (paused)
            bypassedTitle += " (" + getString(R.string.protection_temporary) + ")";
        options.add(new StateOption(AppProtectionState.BYPASSED, bypassedTitle,
                getString(R.string.app_state_bypassed_explanation),
                shownState == AppProtectionState.BYPASSED, true));
        return Collections.unmodifiableList(options);
    }

    private BlockedState buildBlockedState(AppProtectionState state) {
        if (!blockedTrackersLoaded) {
            return new BlockedState(false, null, Collections.emptyList());
        }
        if (blockedRecordingUnavailable) {
            return new BlockedState(false, getString(R.string.protection_recording_unavailable),
                    Collections.emptyList());
        }

        SharedPreferences trackerProtect = getSharedPreferences("tracker_protect", MODE_PRIVATE);
        boolean trackerProtectionEnabled = BlockingMode.isTrackerProtectionEnabled(
                this, trackerProtect, appPackageName);
        boolean minimal = BlockingMode.isMinimalMode(this);
        boolean minimalOnly = state == AppProtectionState.MINIMAL_ONLY;
        boolean readOnlyMinimal = minimal || minimalOnly;
        TrackerBlocklist blocklist = TrackerBlocklist.getInstance(this);
        List<CategoryModel> categories = new ArrayList<>();
        if (blockedTrackerCategories != null) {
            for (TrackerCategory category : blockedTrackerCategories) {
                categories.add(buildCategoryModel(category, blocklist, trackerProtectionEnabled,
                        minimal, readOnlyMinimal, state));
            }
        }
        String message = categories.isEmpty()
                ? getString(R.string.protection_no_blocked_trackers) : null;
        return new BlockedState(true, message, Collections.unmodifiableList(categories));
    }

    private CategoryModel buildCategoryModel(TrackerCategory category, TrackerBlocklist blocklist,
            boolean trackerProtectionEnabled, boolean minimal, boolean readOnlyMinimal,
            AppProtectionState state) {
        String categoryName = category.getCategoryName();
        String categoryDisplayName = category.getDisplayName(this);
        boolean categoryMinimallyBlocked = readOnlyMinimal && category.getChildren().stream()
                .anyMatch(TrackerList::isMinimallyBlocked);
        boolean categoryChecked = readOnlyMinimal
                ? trackerProtectionEnabled && categoryMinimallyBlocked
                : blocklist.blocked(appUid, categoryName);
        boolean categoryEnabled = !readOnlyMinimal && state == AppProtectionState.PROTECTED;
        List<CompanyModel> companies = new ArrayList<>();
        for (Tracker tracker : category.getChildren()) {
            TrackerStatusLogic.Result status = TrackerStatusLogic.resolve(
                    trackerProtectionEnabled,
                    minimal,
                    BlockingMode.isStrictMode(this),
                    state,
                    TrackerList.isMinimallyBlocked(tracker),
                    TrackerList.isMinimallyKnown(tracker),
                    blocklist.blocked(appUid, tracker.category),
                    blocklist.blocked(appUid, TrackerBlocklist.getBlockingKey(tracker)),
                    tracker.isAllowedInStandardMode());
            boolean statusBlocked = status.status == TrackerStatusLogic.Status.BLOCKED;
            String lastSeen = tracker.lastSeen != null && tracker.lastSeen != 0
                    ? Util.relativeTime(tracker.lastSeen).toString() : null;
            companies.add(new CompanyModel(
                    TrackerBlocklist.getBlockingKey(tracker),
                    tracker.getName(),
                    lastSeen,
                    statusString(status.status),
                    statusBlocked,
                    state == AppProtectionState.PROTECTED && !readOnlyMinimal,
                    getString(statusBlocked
                                    ? R.string.feed_allow_company_in_app
                                    : R.string.feed_block_company_in_app,
                            tracker.getName(), appName)));
        }
        String lastContact = category.lastSeen != null && category.lastSeen != 0
                ? String.format(getString(R.string.feed_section_last_contact),
                        Util.relativeTime(category.lastSeen)) : null;
        String explainer = category.isUncertain() ? getString(R.string.uncertain_entry) : null;
        return new CategoryModel(
                categoryName,
                categoryName,
                categoryDisplayName,
                lastContact,
                explainer,
                categoryChecked,
                categoryEnabled,
                String.format(getString(R.string.toggle_block_category_description), categoryDisplayName),
                Collections.unmodifiableList(companies));
    }

    /**
     * Binds the remote-VPN routing control. Whether an app is filtered and
     * whether it is forwarded through the remote VPN are separate choices
     * (#723), so this never follows the protection state above — it only goes
     * away when there is genuinely nothing to choose.
     */
    private RouteModel buildRouteModel() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean wgEnabled = prefs.getBoolean("wg_enabled", false)
                && !TextUtils.isEmpty(prefs.getString("wg_config", ""));
        boolean applyApp = getSharedPreferences("apply", MODE_PRIVATE)
                .getBoolean(appPackageName, true);
        boolean defaultRoutes = wgEnabled && RemoteRoutingHelper.hasDefaultRoutes(this, prefs);

        RemoteRoutingLogic.Unavailable unavailable =
                RemoteRoutingLogic.getUnavailableReason(wgEnabled, defaultRoutes, applyApp);
        if (unavailable != null) {
            return new RouteModel.Unavailable(explainUnavailable(unavailable));
        }
        boolean tunnelled = routesThroughTunnel();
        return new RouteModel.Available(Arrays.asList(
                new RouteOption(true, getString(R.string.app_route_through),
                        getString(R.string.app_route_through_explanation), tunnelled),
                new RouteOption(false, getString(R.string.app_route_direct),
                        getString(R.string.app_route_direct_explanation), !tunnelled)));
    }

    private void applyRoute(boolean wantsTunnel) {
        if (wantsTunnel == routesThroughTunnel())
            return;

        getSharedPreferences(Rule.PREF_WG_ROUTE, MODE_PRIVATE)
                .edit().putBoolean(appPackageName, wantsTunnel).apply();
        render();

        AsyncTask.execute(() -> {
            Rule.clearCache(this);
            ServiceSinkhole.reload("app routing changed", this, false);
        });
    }

    private boolean routesThroughTunnel() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String mode = RemoteRoutingLogic.normalizeMode(
                prefs.getString(Rule.PREF_WG_ROUTE_MODE, RemoteRoutingLogic.getDefaultMode()));
        return RemoteRoutingLogic.routesThroughTunnel(mode,
                RemoteRoutingHelper.getRouteOverride(this, appPackageName), true);
    }

    private String explainUnavailable(RemoteRoutingLogic.Unavailable unavailable) {
        switch (unavailable) {
            case BYPASSED:
                return getString(R.string.app_route_unavailable_bypassed);
            case PARTIAL_ROUTES:
                return getString(R.string.app_route_unavailable_partial_routes);
            case NO_REMOTE_VPN:
            default:
                return getString(R.string.app_route_unavailable_no_vpn);
        }
    }

    /**
     * The pause alarm fired while this screen was open. Do the revert here too:
     * {@link PausedApps#sweep} is idempotent, so racing the receiver is safe, and
     * doing it means the rebind below never shows a half-reverted state.
     */
    private void onPauseExpired() {
        AsyncTask.execute(() -> {
            PausedApps.sweep(this);
            runOnUiThread(() -> {
                if (!activityDestroyed)
                    updateProtectionState();
            });
        });
    }

    private void pauseOrResume() {
        // Act on what the button offered, not on a fresh read: the pause may have
        // expired between binding and this tap, and resuming an app that is no
        // longer paused is a no-op, while pausing one that just resumed is not.
        if (showingResume) {
            PausedApps.resume(this, appPackageName, appUid);
            updateProtectionState();
            return;
        }

        SharedPreferencesHolder stores = new SharedPreferencesHolder();
        AppProtectionState state = AppProtectionState.resolve(
                stores.apply.getBoolean(appPackageName, true),
                BlockingMode.isTrackerProtectionEnabled(this, stores.trackerProtect, appPackageName),
                InternetBlocklist.getInstance(this).blockedInternet(appUid),
                BlockingMode.isMinimalOnlyApp(this, stores.minimalOnlyPrefs, appPackageName));
        if (state != AppProtectionState.PROTECTED
                && state != AppProtectionState.MINIMAL_ONLY
                && state != AppProtectionState.TRACKERS_ALLOWED)
            return;

        if (Util.lockdownState(this) == Util.LockdownState.ENABLED) {
            Util.areYouSure(this, R.string.protection_pause_lockdown_warning,
                    () -> pauseNow());
        } else {
            pauseNow();
        }
    }

    private void pauseNow() {
        PausedApps.pause(this, appPackageName, appUid);
        updateProtectionState();
    }

    private void loadBlockedTrackers() {
        final int generation = ++blockedTrackerLoadGeneration;
        if (!PreferenceManager.getDefaultSharedPreferences(this).getBoolean("log_app", true)) {
            blockedTrackersLoaded = true;
            blockedRecordingUnavailable = true;
            render();
            return;
        }

        AsyncTask.execute(() -> {
            List<TrackerCategory> categories = TrackerList.getInstance(this)
                    .getBlockedAppTrackers(this, appUid);
            runOnUiThread(() -> {
                if (!activityDestroyed && generation == blockedTrackerLoadGeneration)
                    displayBlockedTrackers(categories);
            });
        });
    }

    private void displayBlockedTrackers(List<TrackerCategory> categories) {
        blockedTrackerCategories = categories == null ? new ArrayList<>() : categories;
        blockedTrackersLoaded = true;
        blockedRecordingUnavailable = false;
        render();
    }

    private void toggleCategory(String categoryKey, boolean checked) {
        if (blockedTrackerCategories == null)
            return;
        TrackerCategory category = null;
        for (TrackerCategory candidate : blockedTrackerCategories) {
            if (TextUtils.equals(categoryKey, candidate.getCategoryName())) {
                category = candidate;
                break;
            }
        }
        if (category == null)
            return;

        TrackerBlocklist blocklist = TrackerBlocklist.getInstance(this);
        if (checked)
            blocklist.block(appUid, category.getCategoryName());
        else {
            blocklist.unblock(appUid, category.getCategoryName());
            android.widget.Toast.makeText(this, R.string.category_unblocked,
                    android.widget.Toast.LENGTH_SHORT).show();
        }
        displayBlockedTrackers(blockedTrackerCategories);
    }

    private void toggleCompany(String blockingKey) {
        if (blockedTrackerCategories == null)
            return;
        Tracker tracker = null;
        for (TrackerCategory category : blockedTrackerCategories) {
            for (Tracker candidate : category.getChildren()) {
                if (TextUtils.equals(blockingKey, TrackerBlocklist.getBlockingKey(candidate))) {
                    tracker = candidate;
                    break;
                }
            }
            if (tracker != null)
                break;
        }
        if (tracker == null)
            return;

        if (InternetBlocklist.getInstance(this).blockedInternet(appUid))
            return;
        if (!BlockingMode.isStrictMode(this) && tracker.isAllowedInStandardMode()) {
            android.widget.Toast.makeText(this, R.string.allowed_shared_ip,
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        TrackerBlocklist blocklist = TrackerBlocklist.getInstance(this);
        if (!blocklist.blocked(appUid, tracker.category)) {
            android.widget.Toast.makeText(this, R.string.category_unblocked_warning,
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (blocklist.blockedTracker(appUid, tracker))
            blocklist.unblock(appUid, tracker);
        else
            blocklist.block(appUid, tracker);
        displayBlockedTrackers(blockedTrackerCategories);
    }

    private void render() {
        if (!activityDestroyed && screenController != null)
            screenController.update(buildScreenModel());
    }

    private String statusString(TrackerStatusLogic.Status status) {
        switch (status) {
            case BLOCKED:
                return getString(R.string.timeline_tracker_blocked);
            case ALLOWED_BY_USER:
                return getString(R.string.feed_allowed_by_you);
            case ALLOWED_SHARED_IP:
                return getString(R.string.feed_allowed_shared_ip);
            case MONITORED:
                return getString(R.string.feed_monitored);
            case ALLOWED:
            default:
                return getString(R.string.timeline_tracker_allowed);
        }
    }

    private String packageLabels(List<String> packages) {
        List<String> labels = new ArrayList<>();
        PackageManager pm = getPackageManager();
        for (String packageName : packages) {
            try {
                labels.add(pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString());
            } catch (PackageManager.NameNotFoundException ex) {
                labels.add(packageName);
            }
        }
        return TextUtils.join(", ", labels);
    }

    private String stateLabel(AppProtectionState state) {
        switch (state) {
            case TRACKERS_ALLOWED:
                return getString(R.string.app_state_trackers_allowed);
            case MINIMAL_ONLY:
                return getString(R.string.app_state_essential_only);
            case NO_INTERNET:
                return getString(R.string.app_state_no_internet);
            case BYPASSED:
                return getString(R.string.app_state_bypassed);
            case PROTECTED:
            default:
                return getString(R.string.app_state_protected);
        }
    }

    private AppProtectionState currentState() {
        SharedPreferences apply = getSharedPreferences("apply", MODE_PRIVATE);
        SharedPreferences trackerProtect = getSharedPreferences("tracker_protect", MODE_PRIVATE);
        SharedPreferences minimalOnlyPrefs = getSharedPreferences("tracker_essential", MODE_PRIVATE);
        return AppProtectionState.resolve(
                apply.getBoolean(appPackageName, true),
                BlockingMode.isTrackerProtectionEnabled(this, trackerProtect, appPackageName),
                InternetBlocklist.getInstance(this).blockedInternet(appUid),
                BlockingMode.isMinimalOnlyApp(this, minimalOnlyPrefs, appPackageName));
    }

    @Override
    protected void onPause() {
        super.onPause();
        expiryHandler.removeCallbacks(expiryRunnable);
        DetailsActivity.savePrefs(this);
    }

    private final class SharedPreferencesHolder {
        final android.content.SharedPreferences apply =
                getSharedPreferences("apply", MODE_PRIVATE);
        final android.content.SharedPreferences trackerProtect =
                getSharedPreferences("tracker_protect", MODE_PRIVATE);
        final android.content.SharedPreferences minimalOnlyPrefs =
                getSharedPreferences("tracker_essential", MODE_PRIVATE);
    }

}
