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
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
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
import androidx.preference.PreferenceManager;
import androidx.cardview.widget.CardView;

/** Full-screen per-app protection and temporary pause controls. */
public class ProtectionActivity extends AppCompatActivity {
    private String appPackageName;
    private int appUid;
    private String appName;

    private CardView cardPause;
    private TextView tvPauseStatus;
    private TextView tvPauseSharedUid;
    private MaterialButton btnPauseResume;
    private TextView tvLockdownNote;
    private LinearLayout blockedCategories;
    private TextView tvBlockedEmpty;
    private TextView tvRecordingUnavailable;
    private TextView tvStateNoInternetDesc;
    private RadioGroup stateGroup;
    private RadioGroup routeGroup;
    private TextView tvRouteUnavailable;
    private TextView tvRouteTunnelDesc;
    private TextView tvRouteDirectDesc;
    private boolean bindingRoute;
    private List<TrackerCategory> blockedTrackerCategories = new ArrayList<>();
    private boolean bindingState;
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

        cardPause = findViewById(R.id.cardPause);
        tvPauseStatus = findViewById(R.id.tvPauseStatus);
        tvPauseSharedUid = findViewById(R.id.tvPauseSharedUid);
        btnPauseResume = findViewById(R.id.btnPauseResume);
        tvLockdownNote = findViewById(R.id.tvLockdownNote);
        blockedCategories = findViewById(R.id.llBlockedCategories);
        tvBlockedEmpty = findViewById(R.id.tvBlockedEmpty);
        tvRecordingUnavailable = findViewById(R.id.tvRecordingUnavailable);
        tvStateNoInternetDesc = findViewById(R.id.tvStateNoInternetDesc);
        stateGroup = findViewById(R.id.rgAppState);
        routeGroup = findViewById(R.id.rgAppRoute);
        tvRouteUnavailable = findViewById(R.id.tvAppRouteUnavailable);
        tvRouteTunnelDesc = findViewById(R.id.tvRouteTunnelDesc);
        tvRouteDirectDesc = findViewById(R.id.tvRouteDirectDesc);

        routeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (bindingRoute)
                return;

            applyRoute(checkedId == R.id.rbRouteTunnel);
        });

        btnPauseResume.setOnClickListener(v -> pauseOrResume());
        stateGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (bindingState)
                return;

            AppProtectionState selected = stateForRadioId(checkedId);
            if (selected == null)
                return;

            AppProtectionWriter.applyManual(this, appPackageName, appUid,
                    AppProtectionState.of(selected));
            updateProtectionState();
        });

        AppBarLayout appBar = findViewById(R.id.appbar);
        final int appBarInitialTop = appBar.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(appBar, (v, insets) -> {
            Insets sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), appBarInitialTop + sysBars.top,
                    v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });

        View scroll = findViewById(R.id.protectionScroll);
        final int scrollInitialBottom = scroll.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            Insets sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                    scrollInitialBottom + sysBars.bottom);
            return insets;
        });

        tvLockdownNote.setVisibility(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? View.VISIBLE : View.GONE);
        updateProtectionState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (stateGroup != null) {
            updateProtectionState();
            loadBlockedTrackers();
        }
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
        SharedPreferencesHolder stores = new SharedPreferencesHolder();
        AppProtectionState state = AppProtectionState.resolve(
                stores.apply.getBoolean(appPackageName, true),
                BlockingMode.isTrackerProtectionEnabled(this, stores.trackerProtect, appPackageName),
                InternetBlocklist.getInstance(this).blockedInternet(appUid));
        boolean paused = PausedApps.isPaused(this, appPackageName);

        List<String> sharedPackages = PausedApps.getSharedUidPackages(this, appPackageName, appUid);
        // The pause ends on its own. Rebind once when it does, so a screen left
        // open never shows a countdown that has run out. One message, armed only
        // while this screen is in the foreground, so nothing polls.
        expiryHandler.removeCallbacks(expiryRunnable);
        showingResume = paused;
        if (paused) {
            cardPause.setVisibility(View.VISIBLE);
            int remainingMinutes = PausedApps.getRemainingMinutes(this, appPackageName);
            tvPauseStatus.setText(getResources().getQuantityString(
                    R.plurals.protection_paused_resumes, remainingMinutes, remainingMinutes));
            btnPauseResume.setText(R.string.protection_resume);
            expiryHandler.postDelayed(expiryRunnable,
                    PausedApps.remainingMillis(this, appPackageName) + 1_000L);
        } else if (state == AppProtectionState.PROTECTED
                || state == AppProtectionState.TRACKERS_ALLOWED) {
            cardPause.setVisibility(View.VISIBLE);
            tvPauseStatus.setText(stateLabel(state));
            int pauseMinutes = PausedApps.getConfiguredDurationMinutes(this);
            btnPauseResume.setText(getResources().getQuantityString(R.plurals.pause,
                    pauseMinutes, pauseMinutes));
        } else {
            // No-internet and permanent bypass are not temporary pause targets.
            cardPause.setVisibility(View.GONE);
        }
        tvLockdownNote.setVisibility(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && cardPause.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE);

        if (cardPause.getVisibility() == View.VISIBLE && !sharedPackages.isEmpty()) {
            tvPauseSharedUid.setVisibility(View.VISIBLE);
            tvPauseSharedUid.setText(getString(R.string.protection_pause_shared_uid,
                    packageLabels(sharedPackages)));
        } else {
            tvPauseSharedUid.setVisibility(View.GONE);
        }

        AppProtectionState shownState = paused ? AppProtectionState.BYPASSED : state;
        bindingState = true;
        stateGroup.clearCheck();
        stateGroup.check(radioIdFor(shownState));
        bindingState = false;

        RadioButton bypass = findViewById(R.id.rbStateBypassed);
        bypass.setText(paused
                ? getString(R.string.app_state_bypassed) + " (" + getString(R.string.protection_temporary) + ")"
                : getString(R.string.app_state_bypassed));

        String noInternetExplanation = getString(R.string.app_state_no_internet_explanation);
        if (!sharedPackages.isEmpty())
            noInternetExplanation = getString(R.string.app_state_no_internet_explanation_shared,
                    noInternetExplanation,
                    getString(R.string.app_state_no_internet_shared_uid, packageLabels(sharedPackages)));
        tvStateNoInternetDesc.setText(noInternetExplanation);

        // A pause, a resume, an expiry and the radio above all write the same
        // "apply" preference the routing control reads: a bypassed app has no
        // traffic to route. Rebinding here means no caller can change the
        // protection state and leave a stale routing control behind.
        updateRouteState();
    }

    /**
     * Binds the remote-VPN routing control. Whether an app is filtered and
     * whether it is forwarded through the remote VPN are separate choices
     * (#723), so this never follows the protection state above — it only goes
     * away when there is genuinely nothing to choose.
     */
    private void updateRouteState() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean wgEnabled = prefs.getBoolean("wg_enabled", false)
                && !TextUtils.isEmpty(prefs.getString("wg_config", ""));
        boolean applyApp = getSharedPreferences("apply", MODE_PRIVATE)
                .getBoolean(appPackageName, true);
        boolean defaultRoutes = wgEnabled && RemoteRoutingHelper.hasDefaultRoutes(this, prefs);

        RemoteRoutingLogic.Unavailable unavailable =
                RemoteRoutingLogic.getUnavailableReason(wgEnabled, defaultRoutes, applyApp);
        if (unavailable != null) {
            routeGroup.setVisibility(View.GONE);
            tvRouteTunnelDesc.setVisibility(View.GONE);
            tvRouteDirectDesc.setVisibility(View.GONE);
            tvRouteUnavailable.setVisibility(View.VISIBLE);
            tvRouteUnavailable.setText(explainUnavailable(unavailable));
            return;
        }

        tvRouteUnavailable.setVisibility(View.GONE);
        routeGroup.setVisibility(View.VISIBLE);
        tvRouteTunnelDesc.setVisibility(View.VISIBLE);
        tvRouteDirectDesc.setVisibility(View.VISIBLE);

        bindingRoute = true;
        routeGroup.check(routesThroughTunnel() ? R.id.rbRouteTunnel : R.id.rbRouteDirect);
        bindingRoute = false;
    }

    private void applyRoute(boolean wantsTunnel) {
        if (wantsTunnel == routesThroughTunnel())
            return;

        getSharedPreferences(Rule.PREF_WG_ROUTE, MODE_PRIVATE)
                .edit().putBoolean(appPackageName, wantsTunnel).apply();

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
            runOnUiThread(this::updateProtectionState);
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
                InternetBlocklist.getInstance(this).blockedInternet(appUid));
        if (state != AppProtectionState.PROTECTED && state != AppProtectionState.TRACKERS_ALLOWED)
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
        if (!PreferenceManager.getDefaultSharedPreferences(this).getBoolean("log_app", true)) {
            blockedCategories.setVisibility(View.GONE);
            tvBlockedEmpty.setVisibility(View.GONE);
            tvRecordingUnavailable.setVisibility(View.VISIBLE);
            return;
        }

        AsyncTask.execute(() -> {
            List<TrackerCategory> categories = TrackerList.getInstance(this)
                    .getBlockedAppTrackers(this, appUid);
            runOnUiThread(() -> displayBlockedTrackers(categories));
        });
    }

    private void displayBlockedTrackers(List<TrackerCategory> categories) {
        blockedTrackerCategories = categories;
        blockedCategories.removeAllViews();
        tvRecordingUnavailable.setVisibility(View.GONE);
        if (categories.isEmpty()) {
            blockedCategories.setVisibility(View.GONE);
            tvBlockedEmpty.setVisibility(View.VISIBLE);
            return;
        }

        tvBlockedEmpty.setVisibility(View.GONE);
        blockedCategories.setVisibility(View.VISIBLE);
        for (TrackerCategory category : categories) {
            View item = getLayoutInflater().inflate(R.layout.item_protection_category,
                    blockedCategories, false);
            TextView categoryName = item.findViewById(R.id.tvProtectionCategory);
            TextView categoryTime = item.findViewById(R.id.tvProtectionCategoryTime);
            TextView uncertain = item.findViewById(R.id.tvProtectionUncertain);
            MaterialSwitch categorySwitch = item.findViewById(R.id.switchProtectionCategory);
            ChipGroup chipGroup = item.findViewById(R.id.chipGroupProtectionCompanies);
            categoryName.setText(category.getDisplayName(this));
            categoryTime.setText(Util.relativeTime(category.lastSeen));
            uncertain.setVisibility(category.isUncertain() ? View.VISIBLE : View.GONE);

            TrackerBlocklist blocklist = TrackerBlocklist.getInstance(this);
            SharedPreferences trackerProtect = getSharedPreferences("tracker_protect", MODE_PRIVATE);
            boolean trackerProtectionEnabled = BlockingMode.isTrackerProtectionEnabled(
                    this, trackerProtect, appPackageName);
            boolean minimal = BlockingMode.isMinimalMode(this);
            AppProtectionState state = currentState();
            categorySwitch.setEnabled(!minimal && state == AppProtectionState.PROTECTED);
            categorySwitch.setChecked(minimal
                    ? trackerProtectionEnabled && !TrackerBlocklist.NECESSARY_CATEGORY.equals(category.getCategoryName())
                    : blocklist.blocked(appUid, category.getCategoryName()));
            categorySwitch.setOnCheckedChangeListener((buttonView, checked) -> {
                if (!buttonView.isPressed())
                    return;
                if (checked)
                    blocklist.block(appUid, category.getCategoryName());
                else {
                    blocklist.unblock(appUid, category.getCategoryName());
                    android.widget.Toast.makeText(this, R.string.category_unblocked,
                            android.widget.Toast.LENGTH_SHORT).show();
                }
                displayBlockedTrackers(blockedTrackerCategories);
            });

            for (Tracker tracker : category.getChildren()) {
                Chip chip = new Chip(this);
                boolean ambiguousAllowed = state == AppProtectionState.PROTECTED
                        && !minimal
                        && !BlockingMode.isStrictMode(this)
                        && tracker.isAllowedInStandardMode();
                boolean companyBlocked = state == AppProtectionState.PROTECTED
                        && trackerProtectionEnabled
                        && (minimal ? TrackerBlocklist.blockedTrackerMinimal(tracker)
                        : blocklist.blockedTracker(appUid, tracker));
                String status = getString(ambiguousAllowed ? R.string.allowed_shared_ip
                        : (companyBlocked ? R.string.blocked : R.string.allowed));
                chip.setText(tracker.getName() + "  " + status);
                chip.setTextColor(ContextCompat.getColor(this, R.color.colorPrimary));
                chip.setChipStrokeColorResource(R.color.colorPrimaryLight);
                chip.setChipStrokeWidth(1f);
                chip.setEnsureMinTouchTargetSize(false);
                boolean actionable = state == AppProtectionState.PROTECTED && !minimal;
                chip.setEnabled(actionable);
                if (actionable)
                    chip.setOnClickListener(v -> {
                        if (InternetBlocklist.getInstance(this).blockedInternet(appUid))
                            return;
                        if (!BlockingMode.isStrictMode(this) && tracker.isAllowedInStandardMode()) {
                            android.widget.Toast.makeText(this, R.string.allowed_shared_ip,
                                    android.widget.Toast.LENGTH_SHORT).show();
                            return;
                        }
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
                    });
                chipGroup.addView(chip);
            }
            blockedCategories.addView(item);
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
        return AppProtectionState.resolve(
                apply.getBoolean(appPackageName, true),
                BlockingMode.isTrackerProtectionEnabled(this, trackerProtect, appPackageName),
                InternetBlocklist.getInstance(this).blockedInternet(appUid));
    }

    private static int radioIdFor(AppProtectionState state) {
        switch (state) {
            case TRACKERS_ALLOWED:
                return R.id.rbStateTrackersAllowed;
            case NO_INTERNET:
                return R.id.rbStateNoInternet;
            case BYPASSED:
                return R.id.rbStateBypassed;
            case PROTECTED:
            default:
                return R.id.rbStateProtected;
        }
    }

    private static AppProtectionState stateForRadioId(int checkedId) {
        if (checkedId == R.id.rbStateProtected)
            return AppProtectionState.PROTECTED;
        if (checkedId == R.id.rbStateTrackersAllowed)
            return AppProtectionState.TRACKERS_ALLOWED;
        if (checkedId == R.id.rbStateNoInternet)
            return AppProtectionState.NO_INTERNET;
        if (checkedId == R.id.rbStateBypassed)
            return AppProtectionState.BYPASSED;
        return null;
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
    }
}
