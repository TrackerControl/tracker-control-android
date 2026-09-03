package net.kollnig.missioncontrol;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.content.pm.PackageManager;
import android.text.InputType;
import android.text.TextUtils;
import android.view.MenuItem;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.compose.ui.platform.ComposeView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import net.kollnig.missioncontrol.ui.compose.WireGuardProfileRow;
import net.kollnig.missioncontrol.ui.compose.WireGuardProfilesScreen;
import net.kollnig.missioncontrol.ui.compose.WireGuardProfilesScreenCallbacks;
import net.kollnig.missioncontrol.ui.compose.WireGuardProfilesScreenController;
import net.kollnig.missioncontrol.ui.compose.WireGuardProfilesScreenModel;
import net.kollnig.missioncontrol.wg.WgImporter;
import net.kollnig.missioncontrol.wg.WgProfileManager;
import net.kollnig.missioncontrol.wg.WgConfigParser;
import net.kollnig.missioncontrol.wg.MullvadProfileGenerator;

import org.json.JSONException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import eu.faircode.netguard.ServiceSinkhole;
import eu.faircode.netguard.Util;

public class ActivityWireGuardProfiles extends AppCompatActivity {
    // Lets other screens (the VPN tab) jump straight to a setup route instead
    // of hiding it behind the add-profile dialog.
    public static final String EXTRA_SETUP = "setup";
    public static final String SETUP_IMPORT = "import";
    public static final String SETUP_PROTON = "proton";

    private static final String PROTON_DASHBOARD_URL =
            "https://account.protonvpn.com/downloads#wireguard-configuration";

    private WgProfileManager manager;
    private WireGuardProfilesScreenController screenController;
    private ActivityResultLauncher<Intent> scanLauncher;
    private ActivityResultLauncher<String[]> importLauncher;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Util.setTheme(this);
        super.onCreate(savedInstanceState);

        ComposeView composeView = new ComposeView(this);
        // A stable ID gives Compose's saveable state registry a key to persist
        // under, so the LazyColumn scroll position survives recreation.
        composeView.setId(R.id.compose_wg_profiles);
        setContentView(composeView);

        getSupportActionBar().setTitle(R.string.setting_wg_profile_manage);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        manager = new WgProfileManager(this);
        manager.migrateIfNeeded();

        scanLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null)
                        return;
                    onQrScanned(result.getData().getStringExtra(ActivityScanQr.EXTRA_RESULT));
                });

        importLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                    if (uris != null && !uris.isEmpty())
                        importFromFiles(uris);
                });

        screenController = WireGuardProfilesScreen.install(
                composeView,
                buildScreenModel(),
                new WireGuardProfilesScreenCallbacks() {
                    @Override
                    public void onAdd() {
                        showAddProfileChoice();
                    }

                    @Override
                    public void onEdit(String id) {
                        WgProfileManager.Profile profile = manager.getProfile(id);
                        if (profile != null)
                            showProfileDialog(profile);
                    }

                    @Override
                    public void onDelete(String id) {
                        WgProfileManager.Profile profile = manager.getProfile(id);
                        if (profile != null)
                            confirmDelete(profile);
                    }
                });

        refresh();

        if (savedInstanceState == null) {
            String setup = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_SETUP);
            if (SETUP_PROTON.equals(setup))
                showProtonDialog();
            else if (SETUP_IMPORT.equals(setup))
                showProfileDialog(null);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void refresh() {
        screenController.update(buildScreenModel());
    }

    private WireGuardProfilesScreenModel buildScreenModel() {
        String activeId = manager.getActiveProfileId();
        List<WireGuardProfileRow> rows = new ArrayList<>();
        for (WgProfileManager.Profile profile : manager.getProfiles()) {
            boolean active = profile.id.equals(activeId);
            rows.add(new WireGuardProfileRow(
                    profile.id,
                    profile.name,
                    manager.getProfileSummary(profile),
                    active,
                    getString(R.string.msg_wg_profile_active)));
        }
        return new WireGuardProfilesScreenModel(
                rows, getString(R.string.setting_wg_profile_save));
    }

    private void showAddProfileChoice() {
        new MaterialAlertDialogBuilder(this)
                .setItems(new CharSequence[]{
                        getString(R.string.setting_wg_profile_import_file),
                        getString(R.string.setting_wg_profile_import),
                        getString(R.string.setting_wg_profile_scan),
                        getString(R.string.setting_wg_mullvad_setup),
                        getString(R.string.setting_wg_proton_setup)
                }, (dialog, which) -> {
                    if (which == 0)
                        startFileImport();
                    else if (which == 1)
                        showProfileDialog(null);
                    else if (which == 2)
                        startScan();
                    else if (which == 3)
                        showMullvadDialog();
                    else
                        showProtonDialog();
                })
                .show();
    }

    // Providers hand out one .conf per server, often twenty at a time in a
    // zip, and pasting each one by hand is the complaint behind issue #904.
    private void startFileImport() {
        try {
            // Configs carry no registered MIME type and file managers report
            // them inconsistently, so filtering by type would hide the very
            // files the user came to pick.
            importLauncher.launch(new String[]{"*/*"});
        } catch (ActivityNotFoundException ex) {
            Toast.makeText(this, getString(R.string.msg_wg_profile_import_failed,
                    ex.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void importFromFiles(List<Uri> uris) {
        executor.execute(() -> {
            List<WgProfileManager.ImportEntry> entries = new ArrayList<>();
            // Shared so that picking several files whose names collide keeps
            // every one of them.
            Set<String> names = new HashSet<>();
            int skipped = 0;
            for (Uri uri : uris) {
                String displayName = queryDisplayName(uri);
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) {
                        skipped++;
                        continue;
                    }
                    WgImporter.Result result = WgImporter.read(in, displayName, names);
                    for (WgImporter.Entry entry : result.entries)
                        entries.add(new WgProfileManager.ImportEntry(entry.name, entry.config));
                    skipped += result.skipped.size();
                } catch (IOException | SecurityException ex) {
                    skipped++;
                }
            }

            final int failed = skipped;
            mainHandler.post(() -> finishImport(entries, failed));
        });
    }

    private void finishImport(List<WgProfileManager.ImportEntry> entries, int skipped) {
        if (isFinishing() || isDestroyed())
            return;
        if (entries.isEmpty()) {
            Toast.makeText(this, R.string.msg_wg_profile_import_none, Toast.LENGTH_LONG).show();
            return;
        }
        try {
            WgProfileManager.ImportResult result = manager.importProfiles(entries);
            applyProfiles();
            refresh();
            StringBuilder message = new StringBuilder(getResources().getQuantityString(
                    R.plurals.msg_wg_profile_imported, result.total(), result.total()));
            if (skipped > 0)
                message.append('\n').append(getResources().getQuantityString(
                        R.plurals.msg_wg_profile_import_skipped, skipped, skipped));
            Toast.makeText(this, message.toString(), Toast.LENGTH_LONG).show();
        } catch (JSONException ex) {
            Toast.makeText(this, ex.toString(), Toast.LENGTH_LONG).show();
        }
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (!TextUtils.isEmpty(name))
                    return name;
            }
        } catch (Throwable ignored) {
            // Not every provider answers OpenableColumns; the path is a fine
            // fallback, and naming is cosmetic either way.
        }
        String path = uri.getLastPathSegment();
        return path == null ? "" : path;
    }

    // Proton documents downloading a standard WireGuard config for third-party
    // clients. TrackerControl only guides users through that credential-free
    // route and imports the result as a regular custom profile.
    private void showProtonDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.setting_wg_proton_setup)
                .setMessage(R.string.msg_wg_proton_note)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.msg_wg_proton_open_dashboard, (dialog, which) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW,
                                Uri.parse(PROTON_DASHBOARD_URL)));
                    } catch (ActivityNotFoundException ex) {
                        Toast.makeText(this, R.string.msg_no_browser, Toast.LENGTH_LONG).show();
                    }
                })
                .setPositiveButton(R.string.setting_wg_profile_import, (dialog, which) ->
                        showProfileDialog(null))
                .show();
    }

    private void showMullvadDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        form.setPadding(pad, pad / 2, pad, 0);

        final EditText account = new EditText(this);
        account.setSingleLine(true);
        account.setHint(R.string.msg_wg_mullvad_account);
        account.setInputType(InputType.TYPE_CLASS_NUMBER);
        account.setText(manager.getLastMullvadAccount());
        form.addView(account, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final Spinner country = new Spinner(this);
        List<MullvadProfileGenerator.CountryOption> options = new ArrayList<>();
        options.add(new MullvadProfileGenerator.CountryOption("", getString(R.string.msg_wg_mullvad_recommended)));
        ArrayAdapter<MullvadProfileGenerator.CountryOption> countryAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, options);
        countryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        country.setAdapter(countryAdapter);
        form.addView(country, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView note = new TextView(this);
        note.setText(R.string.msg_wg_mullvad_note);
        note.setPadding(0, pad / 2, 0, 0);
        form.addView(note, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.setting_wg_mullvad_setup)
                .setView(form)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String accountNumber = account.getText().toString().trim();
                if (TextUtils.isEmpty(accountNumber)) {
                    account.setError(getString(R.string.msg_wg_mullvad_account));
                    return;
                }
                MullvadProfileGenerator.CountryOption selected =
                        (MullvadProfileGenerator.CountryOption) country.getSelectedItem();
                dialog.dismiss();
                generateMullvadProfile(accountNumber, selected == null ? "" : selected.code);
            });
            loadMullvadCountries(countryAdapter);
        });
        dialog.show();
    }

    private void loadMullvadCountries(ArrayAdapter<MullvadProfileGenerator.CountryOption> adapter) {
        executor.execute(() -> {
            try {
                List<MullvadProfileGenerator.CountryOption> countries =
                        new MullvadProfileGenerator().fetchCountryOptions();
                mainHandler.post(() -> {
                    adapter.clear();
                    adapter.add(new MullvadProfileGenerator.CountryOption(
                            "", getString(R.string.msg_wg_mullvad_recommended)));
                    adapter.addAll(countries);
                    adapter.notifyDataSetChanged();
                });
            } catch (Throwable ex) {
                mainHandler.post(() -> Toast.makeText(this,
                        getString(R.string.msg_wg_mullvad_countries_failed, ex.getMessage()),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private void generateMullvadProfile(String accountNumber, String countryCode) {
        AlertDialog progress = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.setting_wg_mullvad_setup)
                .setMessage(R.string.msg_wg_mullvad_generating)
                .setCancelable(false)
                .create();
        progress.show();

        executor.execute(() -> {
            try {
                MullvadProfileGenerator.GeneratedProfile generated =
                        new MullvadProfileGenerator().generate(accountNumber, countryCode,
                                manager.getReusableMullvadConfig(accountNumber));
                WgConfigParser.INSTANCE.parse(generated.config);
                mainHandler.post(() -> {
                    progress.dismiss();
                    try {
                        manager.saveMullvadAccount(generated.accountNumber);
                        manager.saveMullvadDeviceId(generated.deviceId);
                        manager.saveProfile(null, generated.name, generated.config,
                                "mullvad", generated.accountNumber,
                                generated.countryCode, generated.countryName);
                        applyProfiles();
                        refresh();
                        Toast.makeText(this, R.string.msg_wg_profile_saved, Toast.LENGTH_LONG).show();
                    } catch (JSONException ex) {
                        Toast.makeText(this, ex.toString(), Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Throwable ex) {
                mainHandler.post(() -> {
                    progress.dismiss();
                    Toast.makeText(this,
                            getString(R.string.msg_wg_mullvad_failed, ex.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    // WireGuard providers routinely publish a config as a QR code; scanning it
    // saves users from moving a config file onto the phone by hand.
    private void startScan() {
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            Toast.makeText(this, R.string.msg_wg_profile_scan_no_camera, Toast.LENGTH_LONG).show();
            return;
        }
        scanLauncher.launch(new Intent(this, ActivityScanQr.class));
    }

    private void onQrScanned(String text) {
        if (TextUtils.isEmpty(text))
            return;
        try {
            WgConfigParser.INSTANCE.parse(text);
        } catch (Throwable ex) {
            // Any QR code will scan, so tell the user when it was not a config
            // rather than dropping them into an import dialog full of noise.
            Toast.makeText(this, getString(R.string.msg_wg_profile_scan_not_config,
                    ex.getMessage()), Toast.LENGTH_LONG).show();
            return;
        }
        showProfileDialog(null, text);
    }

    private void showProfileDialog(WgProfileManager.Profile item) {
        showProfileDialog(item, null);
    }

    private void showProfileDialog(WgProfileManager.Profile item, String prefillConfig) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        form.setPadding(pad, pad / 2, pad, 0);

        final EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint(R.string.msg_wg_profile_name);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        if (item != null)
            name.setText(item.name);
        form.addView(name, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final EditText config = new EditText(this);
        config.setMinLines(10);
        config.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        config.setHint(R.string.msg_wg_profile_config_hint);
        config.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE |
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        if (item != null)
            config.setText(item.config);
        else if (prefillConfig != null)
            config.setText(prefillConfig);
        form.addView(config, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(item == null ? R.string.setting_wg_profile_save : R.string.setting_wg_profile)
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null);

        boolean canActivate = item != null && !item.id.equals(manager.getActiveProfileId());
        if (canActivate)
            builder.setNeutralButton(R.string.msg_wg_profile_set_active, null);

        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String profileName = name.getText().toString().trim();
                String profileConfig = config.getText().toString().trim();
                if (TextUtils.isEmpty(profileName)) {
                    name.setError(getString(R.string.msg_wg_profile_name));
                    return;
                }
                if (TextUtils.isEmpty(profileConfig)) {
                    config.setError(getString(R.string.summary_wg_config));
                    return;
                }
                try {
                    WgConfigParser.INSTANCE.parse(profileConfig);
                    manager.saveProfile(item == null ? null : item.id, profileName, profileConfig);
                    applyProfiles();
                    refresh();
                    Toast.makeText(this, R.string.msg_wg_profile_saved, Toast.LENGTH_LONG).show();
                    dialog.dismiss();
                } catch (JSONException ex) {
                    Toast.makeText(this, ex.toString(), Toast.LENGTH_LONG).show();
                } catch (Throwable ex) {
                    Toast.makeText(this,
                            getString(R.string.msg_wg_config_invalid, ex.getMessage()),
                            Toast.LENGTH_LONG).show();
                }
            });

            if (canActivate)
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                    manager.setActiveProfile(item.id);
                    applyProfiles();
                    refresh();
                    dialog.dismiss();
                });
        });
        dialog.show();
    }

    private void confirmDelete(WgProfileManager.Profile item) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.setting_wg_profile_delete)
                .setMessage(R.string.msg_wg_profile_delete_confirm)
                .setPositiveButton(android.R.string.yes, (dialog, which) -> {
                    manager.deleteProfile(item.id);
                    applyProfiles();
                    refresh();
                    Toast.makeText(this, R.string.msg_wg_profile_deleted, Toast.LENGTH_LONG).show();
                })
                .setNegativeButton(android.R.string.no, null)
                .show();
    }

    private void applyProfiles() {
        ServiceSinkhole.reload("wireguard profile changed", this, false);
    }
}
