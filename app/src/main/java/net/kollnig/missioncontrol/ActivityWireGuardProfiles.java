package net.kollnig.missioncontrol;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import net.kollnig.missioncontrol.wg.WgProfileManager;
import net.kollnig.missioncontrol.wg.WgConfigParser;
import net.kollnig.missioncontrol.wg.MullvadProfileGenerator;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import eu.faircode.netguard.ServiceSinkhole;
import eu.faircode.netguard.Util;

public class ActivityWireGuardProfiles extends AppCompatActivity {
    private WgProfileManager manager;
    private ProfileAdapter adapter;
    private TextView empty;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Util.setTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wg_profiles);

        getSupportActionBar().setTitle(R.string.setting_wg_profile_manage);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        manager = new WgProfileManager(this);
        manager.migrateIfNeeded();

        empty = findViewById(R.id.empty);
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ProfileAdapter(this);
        list.setAdapter(adapter);

        FloatingActionButton fab = findViewById(R.id.fab);
        fab.setOnClickListener(v -> showAddProfileChoice());

        refresh();
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
        adapter.refresh();
        empty.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
    }

    private void showAddProfileChoice() {
        new MaterialAlertDialogBuilder(this)
                .setItems(new CharSequence[]{
                        getString(R.string.setting_wg_profile_import),
                        getString(R.string.setting_wg_mullvad_setup)
                }, (dialog, which) -> {
                    if (which == 0)
                        showProfileDialog(null);
                    else
                        showMullvadDialog();
                })
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

    private void showProfileDialog(WgProfileManager.Profile item) {
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

    private class ProfileAdapter extends RecyclerView.Adapter<ProfileAdapter.ViewHolder> {
        private final Context context;
        private List<WgProfileManager.Profile> profiles;

        ProfileAdapter(Context context) {
            this.context = context;
            this.profiles = manager.getProfiles();
        }

        void refresh() {
            this.profiles = manager.getProfiles();
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_wg_profile, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            WgProfileManager.Profile item = profiles.get(position);
            boolean active = item.id.equals(manager.getActiveProfileId());
            String summary = manager.getProfileSummary(item);

            holder.textName.setText(item.name);
            holder.textSummary.setText(summary);
            holder.textSummary.setVisibility(TextUtils.isEmpty(summary) ? View.GONE : View.VISIBLE);
            holder.textActive.setVisibility(active ? View.VISIBLE : View.GONE);
            holder.itemView.setOnClickListener(v -> showProfileDialog(item));
            holder.btnDelete.setOnClickListener(v -> confirmDelete(item));
        }

        @Override
        public int getItemCount() {
            return profiles.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView textName;
            TextView textSummary;
            TextView textActive;
            ImageButton btnDelete;

            ViewHolder(View itemView) {
                super(itemView);
                textName = itemView.findViewById(R.id.textName);
                textSummary = itemView.findViewById(R.id.textSummary);
                textActive = itemView.findViewById(R.id.textActive);
                btnDelete = itemView.findViewById(R.id.btnDelete);
            }
        }
    }
}
