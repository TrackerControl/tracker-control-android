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
 * Copyright © 2019–2020 Konrad Kollnig (University of Oxford)
 */

package net.kollnig.missioncontrol;

import static net.kollnig.missioncontrol.data.InternetBlocklist.SHARED_PREFS_INTERNET_BLOCKLIST_APPS_KEY;
import static net.kollnig.missioncontrol.data.TrackerBlocklist.PREF_BLOCKLIST;
import static net.kollnig.missioncontrol.data.TrackerBlocklist.SHARED_PREFS_BLOCKLIST_APPS_KEY;

import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.NavUtils;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.opencsv.CSVWriter;

import net.kollnig.missioncontrol.data.InternetBlocklist;
import net.kollnig.missioncontrol.data.PlayStore;
import net.kollnig.missioncontrol.data.Tracker;
import net.kollnig.missioncontrol.data.TrackerBlocklist;
import net.kollnig.missioncontrol.data.TrackerList;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import eu.faircode.netguard.DatabaseHelper;

public class DetailsActivity extends AppCompatActivity {
    public static final String INTENT_EXTRA_APP_PACKAGENAME = "INTENT_APP_PACKAGENAME";
    public static final String INTENT_EXTRA_APP_UID = "INTENT_APP_UID";
    public static final String INTENT_EXTRA_APP_NAME = "INTENT_APP_NAME";
    private static final int REQUEST_EXPORT = 10;
    public static PlayStore.AppInfo app = null;
    private final String TAG = DetailsActivity.class.getSimpleName();
    private boolean running = false;
    private Integer appUid;
    private String appPackageName;
    private DetailsStateAdapter detailsStateAdapter;

    /**
     * Saves the changed tracker settings
     *
     * @param c The context
     */
    public static void savePrefs(Context c) {
        SharedPreferences prefs = c.getSharedPreferences(PREF_BLOCKLIST, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.clear();

        // Tracker settings
        TrackerBlocklist b = TrackerBlocklist.getInstance(c);
        Set<Integer> trackerIntSet = b.getBlocklist();
        Set<String> trackerSet = Common.intToStringSet(trackerIntSet);
        editor.putStringSet(SHARED_PREFS_BLOCKLIST_APPS_KEY, trackerSet);
        for (Integer uid : trackerIntSet) {
            Set<String> subset = b.getSubset(uid);
            editor.putStringSet(SHARED_PREFS_BLOCKLIST_APPS_KEY + "_" + uid, subset);
        }

        // Internet settings
        InternetBlocklist internetBlocklist = InternetBlocklist.getInstance(c);
        Set<String> internetSet = Common.intToStringSet(internetBlocklist.getBlocklist());
        editor.putStringSet(SHARED_PREFS_INTERNET_BLOCKLIST_APPS_KEY, internetSet);

        editor.apply();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, final Intent data) {
        // Export to CSV?
        if (requestCode == REQUEST_EXPORT) {
            if (resultCode == RESULT_OK && data != null)
                new ExportDatabaseCSVTask(data).execute(); // export to CSV
        } else {
            Log.w(TAG, "Unknown activity result request=" + requestCode);
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Enable edge-to-edge content
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_details);

        // Status bar appearance
        WindowInsetsControllerCompat insetsController = new WindowInsetsControllerCompat(getWindow(),
                getWindow().getDecorView());
        insetsController.setAppearanceLightStatusBars(false);
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.colorPrimaryDark));
        }

        // Set window background to primary dark color to show behind the transparent
        // status bar
        getWindow().setBackgroundDrawable(new ColorDrawable(ContextCompat.getColor(this, R.color.colorPrimaryDark)));

        running = true;

        // Receive about details
        Intent intent = getIntent();
        appPackageName = intent.getStringExtra(INTENT_EXTRA_APP_PACKAGENAME);
        appUid = intent.getIntExtra(INTENT_EXTRA_APP_UID, -1);
        String appName = intent.getStringExtra(INTENT_EXTRA_APP_NAME);

        // Set up paging
        detailsStateAdapter = new DetailsStateAdapter(
                this,
                appPackageName,
                appName,
                appUid);
        ViewPager2 viewPager = findViewById(R.id.view_pager);
        viewPager
                .setBackgroundColor(Common.isNight(this) ? android.graphics.Color.BLACK : android.graphics.Color.WHITE);
        viewPager.setAdapter(detailsStateAdapter);
        TabLayout tabs = findViewById(R.id.tabs);
        new TabLayoutMediator(tabs, viewPager,
                (tab, position) -> tab.setText(getString(detailsStateAdapter.getPageTitle(position)))).attach();

        // set toolbar and back arrow
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        Objects.requireNonNull(getSupportActionBar()).setDisplayHomeAsUpEnabled(true);

        // Set title
        getSupportActionBar().setDisplayShowTitleEnabled(false);
        toolbar.setTitle(getString(R.string.app_info));
        toolbar.setSubtitle(appName);

        // Apply window insets so content avoids status/navigation bars
        AppBarLayout appBar = findViewById(R.id.appbar);
        final int appBarInitialLeft = appBar.getPaddingLeft();
        final int appBarInitialTop = appBar.getPaddingTop();
        final int appBarInitialRight = appBar.getPaddingRight();
        final int appBarInitialBottom = appBar.getPaddingBottom();
        final ViewGroup.MarginLayoutParams appBarParams = (ViewGroup.MarginLayoutParams) appBar.getLayoutParams();
        final int appBarInitialMarginTop = appBarParams.topMargin;

        ViewCompat.setOnApplyWindowInsetsListener(appBar, (v, insets) -> {
            Insets sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());

            // Use margin instead of padding for the top inset so the window background
            // shows through
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            params.topMargin = appBarInitialMarginTop + sysBars.top;
            v.setLayoutParams(params);

            v.setPadding(appBarInitialLeft, appBarInitialTop, appBarInitialRight, appBarInitialBottom);
            return insets;
        });

        final int pagerInitialLeft = viewPager.getPaddingLeft();
        final int pagerInitialTop = viewPager.getPaddingTop();
        final int pagerInitialRight = viewPager.getPaddingRight();
        final int pagerInitialBottom = viewPager.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(viewPager, (v, insets) -> {
            Insets sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            int extraPadding = eu.faircode.netguard.Util.dips2pixels(16, this);
            v.setPadding(pagerInitialLeft + sysBars.left, pagerInitialTop, pagerInitialRight + sysBars.right,
                    pagerInitialBottom + sysBars.bottom + extraPadding);
            return insets;
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_details, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();// Respond to the action bar's Up/Home button
        if (itemId == android.R.id.home) {
            NavUtils.navigateUpFromSameTask(this);
            return true;
        } else if (itemId == R.id.action_export_csv) {
            startActivityForResult(getIntentCreateExport(), REQUEST_EXPORT);
            return true;
        } else if (itemId == R.id.action_clear) {
            DatabaseHelper dh = DatabaseHelper.getInstance(this);
            dh.clearAccess(appUid, false);
            detailsStateAdapter.updateTrackerLists();
            return true;
        } else if (itemId == R.id.action_launch) {
            Intent launch = Common.getLaunchIntent(this, appPackageName);
            if (launch != null)
                startActivity(launch);
            return true;
        } else if (itemId == R.id.action_uninstall) {
            Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + appPackageName));
            startActivity(intent);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Creates an intent to save a file. Necessary to determine destination of CSV
     * export.
     *
     * @return File intent
     */
    private Intent getIntentCreateExport() {
        Intent intent;
        intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_TITLE, appPackageName + "_"
                + new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date().getTime()) + ".csv");
        return intent;
    }

    @Override
    public void onPause() {
        super.onPause();
        savePrefs(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        app = null;
        running = false;
    }

    /**
     * Export tracking findings to CSV
     */
    class ExportDatabaseCSVTask extends AsyncTask<String, Void, Boolean> {
        private final ProgressDialog dialog = new ProgressDialog(DetailsActivity.this);
        TrackerList trackerList;
        Intent data;

        public ExportDatabaseCSVTask(Intent data) {
            this.data = data;
        }

        @Override
        protected void onPreExecute() {
            this.dialog.setMessage(getString(R.string.exporting));
            this.dialog.show();
            trackerList = TrackerList.getInstance(DetailsActivity.this);
        }

        protected Boolean doInBackground(final String... args) {
            Uri target = data.getData();
            if (data.hasExtra("org.openintents.extra.DIR_PATH"))
                target = Uri.parse(target + "/" + appPackageName + "_"
                        + new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date().getTime()) + ".csv");
            Log.i(TAG, "Writing URI=" + target);

            try (OutputStream out = getContentResolver().openOutputStream(target)) {
                csvExport(out);
                return true;
            } catch (Throwable ex) {
                Log.e(TAG, ex + "\n" + Log.getStackTraceString(ex));
                return false;
            }
        }

        /**
         * Retrieve information to be exported from database and save to CSV file.
         *
         * @param out The destination for the exported information
         * @throws IOException If saving the CSV fails
         */
        private void csvExport(OutputStream out) throws IOException {
            try (CSVWriter csv = new CSVWriter(new OutputStreamWriter(out),
                    CSVWriter.DEFAULT_SEPARATOR,
                    CSVWriter.DEFAULT_QUOTE_CHARACTER,
                    CSVWriter.DEFAULT_ESCAPE_CHARACTER,
                    CSVWriter.RFC4180_LINE_END)) {

                try (Cursor data = trackerList.getAppInfo(appUid)) {
                    if (data == null)
                        throw new IOException("Could not read hosts.");

                    List<String> columnNames = new ArrayList<>();
                    Collections.addAll(columnNames, data.getColumnNames());
                    columnNames.add("Tracker Name");
                    columnNames.add("Tracker Category");

                    csv.writeNext(columnNames.toArray(new String[0]));
                    while (data.moveToNext()) {
                        String[] row = new String[data.getColumnNames().length + 2];
                        for (int i = 0; i < data.getColumnNames().length; i++) {
                            row[i] = data.getString(i);
                        }

                        String hostname = data.getString(data.getColumnIndexOrThrow("daddr"));
                        Tracker tracker = TrackerList.findTracker(hostname);
                        if (tracker != null) {
                            row[data.getColumnNames().length] = tracker.getName();
                            row[data.getColumnNames().length + 1] = tracker.getCategory();
                        } else {
                            row[data.getColumnNames().length] = "";
                            row[data.getColumnNames().length + 1] = "";
                        }

                        csv.writeNext(row);
                    }
                }
            }
        }

        protected void onPostExecute(final Boolean success) {
            if (running) {
                if (this.dialog.isShowing())
                    this.dialog.dismiss();

                if (success) {
                    View v = findViewById(R.id.view_pager);
                    Snackbar s = Snackbar.make(v, R.string.exported, Snackbar.LENGTH_LONG);
                    s.show();
                } else {
                    Toast.makeText(DetailsActivity.this, R.string.export_failed, Toast.LENGTH_SHORT).show();
                }
            }
        }
    }
}