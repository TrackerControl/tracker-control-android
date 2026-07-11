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
 *
 * Copyright © 2026 Konrad Kollnig (University of Oxford)
 */

package net.kollnig.missioncontrol;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import net.kollnig.missioncontrol.DiagnosticsReport.Finding;
import net.kollnig.missioncontrol.DiagnosticsReport.Severity;

import eu.faircode.netguard.Util;

/**
 * On-demand self-check / diagnostics screen.
 * <p>
 * Renders a plain-language verdict for the two most common bug reports (connectivity
 * and battery) plus a paste-able report the user can copy or share. Everything is a
 * one-shot snapshot computed when the screen opens — no background work, no timers,
 * nothing leaves the device unless the user explicitly copies/shares.
 */
public class ActivityDiagnostics extends AppCompatActivity {

    // Kept readable in both light and dark themes.
    private static final int COLOR_WARN = 0xFFE53935;
    private static final int COLOR_OK = 0xFF43A047;

    private String report = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Util.setTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diagnostics);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.diagnostics_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        final TextView tvReport = findViewById(R.id.tvReport);
        tvReport.setText(R.string.diagnostics_generating);

        Button btnCopy = findViewById(R.id.btnCopy);
        Button btnShare = findViewById(R.id.btnShare);
        btnCopy.setEnabled(false);
        btnShare.setEnabled(false);

        btnCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.diagnostics_title), report));
                Toast.makeText(this, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show();
            }
        });

        btnShare.setOnClickListener(v -> {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.diagnostics_title));
            send.putExtra(Intent.EXTRA_TEXT, report);
            startActivity(Intent.createChooser(send, getString(R.string.diagnostics_share)));
        });

        final Context app = getApplicationContext();
        // One-shot generation off the main thread (local reads only, but be safe).
        new Thread(() -> {
            final DiagnosticsReport result = DiagnosticsReport.generate(app);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed())
                    return;
                report = result.report;
                tvReport.setText(report);
                renderFindings(findViewById(R.id.llConnectivity), result.connectivity);
                renderFindings(findViewById(R.id.llBattery), result.battery);
                btnCopy.setEnabled(true);
                btnShare.setEnabled(true);
            });
        }, "tc-diagnostics").start();
    }

    private void renderFindings(LinearLayout container, java.util.List<Finding> findings) {
        container.removeAllViews();
        for (Finding f : findings) {
            TextView tv = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            tv.setLayoutParams(lp);
            tv.setTextIsSelectable(true);
            tv.setText(symbol(f.severity) + "  " + f.message);
            if (f.severity == Severity.WARN)
                tv.setTextColor(COLOR_WARN);
            else if (f.severity == Severity.OK)
                tv.setTextColor(COLOR_OK);
            container.addView(tv);
        }
    }

    private static String symbol(Severity s) {
        switch (s) {
            case WARN:
                return "⚠"; // ⚠
            case OK:
                return "✓"; // ✓
            default:
                return "ℹ"; // ℹ
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
