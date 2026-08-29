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

package net.kollnig.missioncontrol.details;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.work.Data;
import androidx.work.WorkInfo;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import net.kollnig.missioncontrol.DetailsActivity;
import net.kollnig.missioncontrol.R;
import net.kollnig.missioncontrol.analysis.TrackerAnalysisManager;
import net.kollnig.missioncontrol.analysis.TrackerAnalysisWorker;
import net.kollnig.missioncontrol.analysis.TrackerSignatureManager;
import net.kollnig.missioncontrol.data.ExodusTracker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import eu.faircode.netguard.Util;

/**
 * The static tracker-library report for one app.
 *
 * This is a separate screen from the Trackers tab on purpose: library detection
 * reads the app's code, while the tracker list on that tab is derived from
 * observed DNS traffic. They answer different questions and only the latter is
 * blockable, so mixing them into one scroll made both harder to read.
 */
public class LibrariesActivity extends AppCompatActivity {
    private String appPackageName;
    private String appName;

    private TrackerAnalysisManager manager;

    private MaterialButton btnAnalyze;
    private TextView tvDetectedTrackers;
    private TextView tvDisclaimer;
    private View layoutDetectedTrackersResult;
    private LinearLayout trackerLibrariesList;
    private View layoutProgress;
    private TextView tvAnalysisProgress;
    private LinearProgressIndicator pbDetectedTrackers;
    private final Map<String, String> trackerWebsites = new HashMap<>();
    private final ExecutorService trackerMetadataExecutor = Executors.newSingleThreadExecutor();
    private String currentTrackerResult;
    private boolean currentTrackerResultStale;
    private boolean trackerResultVisible;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_libraries);

        Intent intent = getIntent();
        appPackageName = intent.getStringExtra(DetailsActivity.INTENT_EXTRA_APP_PACKAGENAME);
        appName = intent.getStringExtra(DetailsActivity.INTENT_EXTRA_APP_NAME);
        if (TextUtils.isEmpty(appPackageName)) {
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
        toolbar.setTitle(R.string.libraries_title);
        toolbar.setSubtitle(appName);

        btnAnalyze = findViewById(R.id.btnAnalyzeTrackers);
        tvDetectedTrackers = findViewById(R.id.tvDetectedTrackers);
        tvDisclaimer = findViewById(R.id.tvLibraryDisclaimer);
        layoutDetectedTrackersResult = findViewById(R.id.layoutDetectedTrackersResult);
        trackerLibrariesList = findViewById(R.id.trackerLibrariesList);
        layoutProgress = findViewById(R.id.layoutAnalysisProgress);
        tvAnalysisProgress = findViewById(R.id.tvAnalysisProgress);
        pbDetectedTrackers = findViewById(R.id.pbDetectedTrackers);

        // Play Store builds cannot block, so the explanation must not promise it.
        ((TextView) findViewById(R.id.tvLibraryExplanation)).setText(Util.isPlayStoreInstall()
                ? R.string.trackers_static_explanation_playstore
                : R.string.trackers_static_explanation);

        manager = TrackerAnalysisManager.getInstance(this);
        showCachedResult();
        loadTrackerWebsites();

        btnAnalyze.setOnClickListener(v -> manager.startAnalysis(appPackageName));

        manager.getWorkInfoByPackageLiveData(appPackageName)
                .observe(this, this::onWorkInfoChanged);

        AppBarLayout appBar = findViewById(R.id.appbar);
        final int appBarInitialTop = appBar.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(appBar, (v, insets) -> {
            Insets sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), appBarInitialTop + sysBars.top,
                    v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });

        View scroll = findViewById(R.id.librariesScroll);
        final int scrollInitialBottom = scroll.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            Insets sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                    scrollInitialBottom + sysBars.bottom);
            return insets;
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        trackerMetadataExecutor.shutdownNow();
        super.onDestroy();
    }

    private void showCachedResult() {
        String cachedResults = manager.getCachedResult(appPackageName);
        if (cachedResults == null)
            return;

        boolean stale = manager.isCacheStale(appPackageName);
        renderTrackerResult(cachedResults, stale);
        if (stale) {
            btnAnalyze.setText(R.string.update_analysis);
        }
    }

    private void loadTrackerWebsites() {
        trackerMetadataExecutor.execute(() -> {
            Map<String, String> websites = new HashMap<>();
            List<ExodusTracker> trackers = new TrackerSignatureManager(this).getTrackers();
            if (trackers != null) {
                for (ExodusTracker tracker : trackers) {
                    if (TextUtils.isEmpty(tracker.getName())
                            || TextUtils.isEmpty(tracker.getWebsite()))
                        continue;
                    websites.put(normalizeTrackerName(tracker.getName()), tracker.getWebsite());
                }
            }

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed())
                    return;
                trackerWebsites.clear();
                trackerWebsites.putAll(websites);
                if (trackerResultVisible && currentTrackerResult != null)
                    renderTrackerResult(currentTrackerResult, currentTrackerResultStale);
            });
        });
    }

    private void renderTrackerResult(String result, boolean stale) {
        hideTrackerResult();
        currentTrackerResult = result;
        currentTrackerResultStale = stale;

        List<String> trackerNames = parseTrackerNames(result);
        if (trackerNames.isEmpty()) {
            tvDetectedTrackers.setText(getString(R.string.detected_trackers, result));
            tvDetectedTrackers.setVisibility(View.VISIBLE);
        } else {
            for (String trackerName : trackerNames)
                addTrackerRow(trackerName);
            layoutDetectedTrackersResult.setVisibility(View.VISIBLE);
        }

        String disclaimer = getString(R.string.trackers_static_disclaimer);
        if (stale)
            disclaimer += getString(R.string.analysis_outdated_version);
        tvDisclaimer.setText(disclaimer);
        tvDisclaimer.setVisibility(View.VISIBLE);
        trackerResultVisible = true;
    }

    private void hideTrackerResult() {
        trackerResultVisible = false;
        layoutDetectedTrackersResult.setVisibility(View.GONE);
        trackerLibrariesList.removeAllViews();
        tvDetectedTrackers.setVisibility(View.GONE);
        tvDisclaimer.setVisibility(View.GONE);
    }

    private void addTrackerRow(String trackerName) {
        View row = getLayoutInflater().inflate(
                R.layout.item_tracker_library, trackerLibrariesList, false);
        TextView name = row.findViewById(R.id.tvTrackerLibraryName);
        ImageView linkIcon = row.findViewById(R.id.ivTrackerLibraryLink);
        name.setText(trackerName);

        Uri website = getWebsiteUri(trackerWebsites.get(normalizeTrackerName(trackerName)));
        if (website == null) {
            row.setBackground(null);
            linkIcon.setVisibility(View.GONE);
            name.setAlpha(0.72f);
        } else {
            Intent intent = new Intent(Intent.ACTION_VIEW, website);
            row.setContentDescription(getString(R.string.open_tracker_website, trackerName));
            row.setFocusable(true);
            row.setOnClickListener(v -> {
                try {
                    startActivity(intent);
                } catch (ActivityNotFoundException ignored) {
                    // A browser is optional; keep the detected library visible.
                }
            });
        }

        trackerLibrariesList.addView(row);
    }

    private static Uri getWebsiteUri(String website) {
        if (TextUtils.isEmpty(website))
            return null;

        Uri uri = Uri.parse(website);
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
            return null;
        return uri;
    }

    private static List<String> parseTrackerNames(String result) {
        List<String> trackerNames = new ArrayList<>();
        if (result == null)
            return trackerNames;

        for (String line : result.split("\\n")) {
            String candidate = line.trim();
            if (!candidate.startsWith("•"))
                continue;

            String name = candidate.substring(1).trim();
            if (!name.isEmpty())
                trackerNames.add(name);
        }
        return trackerNames;
    }

    private static String normalizeTrackerName(String name) {
        return name.replaceAll("[°²?µ]", "")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private void onWorkInfoChanged(List<WorkInfo> workInfoList) {
        if (workInfoList == null || workInfoList.isEmpty()) {
            updateAnalysisState(null);
            return;
        }

        // Prefer work that is still running over the most recent finished run.
        WorkInfo activeWork = null;
        for (WorkInfo info : workInfoList) {
            if (!info.getState().isFinished()) {
                activeWork = info;
                break;
            }
        }
        if (activeWork == null)
            activeWork = workInfoList.get(0);

        updateAnalysisState(activeWork);
    }

    private void updateAnalysisState(WorkInfo workInfo) {
        if (workInfo == null) {
            layoutProgress.setVisibility(View.GONE);
            btnAnalyze.setEnabled(true);
            return;
        }

        switch (workInfo.getState()) {
            case ENQUEUED:
            case BLOCKED:
                btnAnalyze.setEnabled(false);
                layoutProgress.setVisibility(View.VISIBLE);
                tvAnalysisProgress.setText(R.string.analysis_queued);
                pbDetectedTrackers.setIndeterminate(true);
                hideTrackerResult();
                break;

            case RUNNING:
                btnAnalyze.setEnabled(false);
                layoutProgress.setVisibility(View.VISIBLE);
                pbDetectedTrackers.setIndeterminate(false);
                hideTrackerResult();

                Data progress = workInfo.getProgress();
                int percent = progress.getInt(TrackerAnalysisWorker.KEY_PROGRESS, 0);
                pbDetectedTrackers.setProgress(percent);
                tvAnalysisProgress.setText(
                        getString(R.string.analyzing_classes_progress, percent));
                break;

            case SUCCEEDED:
                layoutProgress.setVisibility(View.GONE);
                btnAnalyze.setEnabled(true);
                btnAnalyze.setText(R.string.analyze_tracker_libraries);

                String result = workInfo.getOutputData().getString(TrackerAnalysisWorker.KEY_RESULT);
                if (result != null) {
                    renderTrackerResult(result, false);
                } else {
                    // A finished run this screen did not start; fall back to the cache.
                    showCachedResult();
                }
                break;

            case FAILED:
                layoutProgress.setVisibility(View.GONE);
                btnAnalyze.setEnabled(true);
                hideTrackerResult();

                String error = workInfo.getOutputData().getString(TrackerAnalysisWorker.KEY_ERROR);
                if (error != null) {
                    tvDetectedTrackers.setText(error);
                    tvDetectedTrackers.setVisibility(View.VISIBLE);
                }
                break;

            case CANCELLED:
                layoutProgress.setVisibility(View.GONE);
                btnAnalyze.setEnabled(true);
                showCachedResult();
                break;
        }
    }
}
