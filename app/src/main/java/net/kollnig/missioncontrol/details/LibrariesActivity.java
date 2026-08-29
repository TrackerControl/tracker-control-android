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

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;
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

import java.util.List;

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
    private View layoutProgress;
    private TextView tvAnalysisProgress;
    private LinearProgressIndicator pbDetectedTrackers;

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
        layoutProgress = findViewById(R.id.layoutAnalysisProgress);
        tvAnalysisProgress = findViewById(R.id.tvAnalysisProgress);
        pbDetectedTrackers = findViewById(R.id.pbDetectedTrackers);

        // Play Store builds cannot block, so the explanation must not promise it.
        ((TextView) findViewById(R.id.tvLibraryExplanation)).setText(Util.isPlayStoreInstall()
                ? R.string.trackers_static_explanation_playstore
                : R.string.trackers_static_explanation);

        manager = TrackerAnalysisManager.getInstance(this);
        showCachedResult();

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

    private void showCachedResult() {
        String cachedResults = manager.getCachedResult(appPackageName);
        if (cachedResults == null)
            return;

        String res = getString(R.string.detected_trackers, cachedResults);
        if (manager.isCacheStale(appPackageName)) {
            res += getString(R.string.analysis_outdated_version);
            btnAnalyze.setText(R.string.update_analysis);
        }
        tvDetectedTrackers.setText(res);
        tvDetectedTrackers.setVisibility(View.VISIBLE);
        tvDisclaimer.setVisibility(View.VISIBLE);
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
                tvDetectedTrackers.setVisibility(View.GONE);
                tvDisclaimer.setVisibility(View.GONE);
                break;

            case RUNNING:
                btnAnalyze.setEnabled(false);
                layoutProgress.setVisibility(View.VISIBLE);
                pbDetectedTrackers.setIndeterminate(false);
                tvDetectedTrackers.setVisibility(View.GONE);
                tvDisclaimer.setVisibility(View.GONE);

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
                    tvDetectedTrackers.setText(getString(R.string.detected_trackers, result));
                    tvDetectedTrackers.setVisibility(View.VISIBLE);
                    tvDisclaimer.setVisibility(View.VISIBLE);
                } else {
                    // A finished run this screen did not start; fall back to the cache.
                    showCachedResult();
                }
                break;

            case FAILED:
                layoutProgress.setVisibility(View.GONE);
                btnAnalyze.setEnabled(true);

                String error = workInfo.getOutputData().getString(TrackerAnalysisWorker.KEY_ERROR);
                if (error != null) {
                    tvDetectedTrackers.setText(error);
                    tvDetectedTrackers.setVisibility(View.VISIBLE);
                }
                break;

            case CANCELLED:
                layoutProgress.setVisibility(View.GONE);
                btnAnalyze.setEnabled(true);
                break;
        }
    }
}
