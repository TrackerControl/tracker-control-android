package net.kollnig.missioncontrol.vpn;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import net.kollnig.missioncontrol.ActivityWireGuardProfiles;
import net.kollnig.missioncontrol.R;
import net.kollnig.missioncontrol.wg.MullvadProfileGenerator;
import net.kollnig.missioncontrol.wg.WgConfigParser;
import net.kollnig.missioncontrol.wg.WgProfileManager;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import eu.faircode.netguard.ServiceSinkhole;

public class VpnFragment extends Fragment implements SharedPreferences.OnSharedPreferenceChangeListener {
    private SharedPreferences prefs;
    private WgProfileManager manager;
    private CountryAdapter adapter;

    private View countryError;
    private TextView statusFlag;
    private TextView statusTitle;
    private TextView statusSummary;
    private TextView progressText;
    private Button retryCountries;
    private MaterialSwitch enabledSwitch;
    private ProgressBar progress;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<MullvadProfileGenerator.CountryOption> countryCache = new ArrayList<>();

    private boolean suppressSwitchChange;
    private boolean loadingCountries;
    private String generatingCountryCode = "";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_vpn, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        manager = new WgProfileManager(requireContext());
        manager.migrateIfNeeded();

        statusFlag = view.findViewById(R.id.vpnStatusFlag);
        statusTitle = view.findViewById(R.id.vpnStatusTitle);
        statusSummary = view.findViewById(R.id.vpnStatusSummary);
        enabledSwitch = view.findViewById(R.id.vpnEnabledSwitch);
        progress = view.findViewById(R.id.vpnProgress);
        progressText = view.findViewById(R.id.vpnProgressText);
        countryError = view.findViewById(R.id.vpnCountryError);
        retryCountries = view.findViewById(R.id.vpnRetryCountries);
        RecyclerView countries = view.findViewById(R.id.vpnCountryList);
        TextView settings = view.findViewById(R.id.vpnSettings);
        TextView customProfiles = view.findViewById(R.id.vpnCustomProfiles);

        countries.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new CountryAdapter();
        countries.setAdapter(adapter);

        enabledSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            if (suppressSwitchChange)
                return;
            if (checked && manager.getActiveProfile() == null) {
                suppressSwitchChange = true;
                enabledSwitch.setChecked(false);
                suppressSwitchChange = false;
                Toast.makeText(requireContext(), R.string.vpn_choose_country_first,
                        Toast.LENGTH_LONG).show();
                return;
            }
            prefs.edit().putBoolean("wg_enabled", checked).apply();
            ServiceSinkhole.reload("changed wg_enabled", requireContext(), false);
            refreshUi();
        });

        retryCountries.setOnClickListener(v -> loadCountries(true));
        settings.setOnClickListener(v -> showSettingsDialog());
        customProfiles.setOnClickListener(v -> startActivity(
                new Intent(requireContext(), ActivityWireGuardProfiles.class)));

        refreshUi();
        loadCountries(false);
    }

    @Override
    public void onResume() {
        super.onResume();
        prefs.registerOnSharedPreferenceChangeListener(this);
        refreshUi();
    }

    @Override
    public void onPause() {
        super.onPause();
        prefs.unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if ("wg_enabled".equals(key) ||
                WgProfileManager.PREF_WG_PROFILE.equals(key) ||
                WgProfileManager.PREF_WG_PROFILES.equals(key) ||
                WgProfileManager.PREF_MULLVAD_ACCOUNT.equals(key)) {
            refreshUi();
        }
    }

    private void loadCountries(boolean force) {
        if (loadingCountries)
            return;
        if (!force && !countryCache.isEmpty()) {
            adapter.setCountries(countryCache);
            return;
        }

        loadingCountries = true;
        setProgress(getString(R.string.vpn_loading_countries));
        countryError.setVisibility(View.GONE);

        executor.execute(() -> {
            try {
                List<MullvadProfileGenerator.CountryOption> countries =
                        new MullvadProfileGenerator().fetchCountryOptions();
                mainHandler.post(() -> {
                    if (!isAdded())
                        return;
                    loadingCountries = false;
                    clearProgress();
                    countryCache.clear();
                    countryCache.addAll(countries);
                    countryError.setVisibility(View.GONE);
                    adapter.setCountries(countryCache);
                });
            } catch (Throwable ex) {
                mainHandler.post(() -> {
                    if (!isAdded())
                        return;
                    loadingCountries = false;
                    clearProgress();
                    adapter.setCountries(savedMullvadCountries());
                    countryError.setVisibility(View.VISIBLE);
                    Toast.makeText(requireContext(),
                            getString(R.string.vpn_country_load_failed, ex.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private List<MullvadProfileGenerator.CountryOption> savedMullvadCountries() {
        Map<String, String> saved = new LinkedHashMap<>();
        for (WgProfileManager.Profile profile : manager.getProfiles())
            if ("mullvad".equals(profile.provider) &&
                    !TextUtils.isEmpty(profile.countryCode) &&
                    !TextUtils.isEmpty(profile.countryName))
                saved.put(profile.countryCode, profile.countryName);

        List<MullvadProfileGenerator.CountryOption> countries = new ArrayList<>();
        for (Map.Entry<String, String> entry : saved.entrySet())
            countries.add(new MullvadProfileGenerator.CountryOption(entry.getKey(), entry.getValue()));
        return countries;
    }

    private void generateCountry(MullvadProfileGenerator.CountryOption country) {
        String account = manager.getLastMullvadAccount();
        if (TextUtils.isEmpty(account)) {
            showSettingsDialog();
            return;
        }
        if (!TextUtils.isEmpty(generatingCountryCode))
            return;

        generatingCountryCode = country.code;
        setProgress(getString(R.string.vpn_generating));
        adapter.notifyDataSetChanged();

        executor.execute(() -> {
            try {
                MullvadProfileGenerator.GeneratedProfile generated =
                        new MullvadProfileGenerator().generate(account, country.code,
                                manager.getReusableMullvadConfig(account));
                WgConfigParser.INSTANCE.parse(generated.config);
                mainHandler.post(() -> {
                    if (isAdded())
                        saveGeneratedProfile(generated);
                });
            } catch (Throwable ex) {
                mainHandler.post(() -> {
                    if (!isAdded())
                        return;
                    generatingCountryCode = "";
                    clearProgress();
                    adapter.notifyDataSetChanged();
                    Toast.makeText(requireContext(),
                            getString(R.string.vpn_generation_failed, ex.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void saveGeneratedProfile(MullvadProfileGenerator.GeneratedProfile generated) {
        try {
            WgProfileManager.Profile existing =
                    manager.findMullvadProfileForCountry(generated.countryCode);
            manager.saveMullvadAccount(generated.accountNumber);
            manager.saveProfile(existing == null ? null : existing.id,
                    generated.name,
                    generated.config,
                    "mullvad",
                    generated.accountNumber,
                    generated.countryCode,
                    generated.countryName);
            prefs.edit().putBoolean("wg_enabled", true).apply();
            ServiceSinkhole.reload("wireguard profile changed", requireContext(), false);
            generatingCountryCode = "";
            clearProgress();
            refreshUi();
        } catch (JSONException ex) {
            generatingCountryCode = "";
            clearProgress();
            Toast.makeText(requireContext(), ex.toString(), Toast.LENGTH_LONG).show();
        }
    }

    private void refreshUi() {
        if (!isAdded() || manager == null)
            return;

        boolean enabled = prefs.getBoolean("wg_enabled", false);
        suppressSwitchChange = true;
        enabledSwitch.setChecked(enabled);
        suppressSwitchChange = false;

        WgProfileManager.Profile active = manager.getActiveProfile();
        boolean activeMullvad = active != null && "mullvad".equals(active.provider);
        String countryName = activeMullvad ? active.countryName : "";
        String countryCode = activeMullvad ? active.countryCode : "";

        if (enabled && activeMullvad && !TextUtils.isEmpty(countryName)) {
            statusFlag.setText(flagEmoji(countryCode));
            statusTitle.setText(countryName);
            String summary = manager.getProfileSummary(active);
            statusSummary.setText(TextUtils.isEmpty(summary)
                    ? getString(R.string.vpn_status_mullvad)
                    : getString(R.string.vpn_status_relay, summary));
            statusSummary.setVisibility(TextUtils.isEmpty(statusSummary.getText()) ? View.GONE : View.VISIBLE);
        } else if (enabled && active != null) {
            statusFlag.setText("");
            statusTitle.setText(R.string.vpn_status_wireguard_connected);
            String summary = manager.getProfileSummary(active);
            statusSummary.setText(TextUtils.isEmpty(summary)
                    ? active.name
                    : getString(R.string.vpn_status_wireguard_summary, active.name, summary));
            statusSummary.setVisibility(View.VISIBLE);
        } else {
            statusFlag.setText("");
            statusTitle.setText(R.string.vpn_status_disconnected);
            statusSummary.setVisibility(View.GONE);
        }

        adapter.notifyDataSetChanged();
    }

    private void setProgress(String text) {
        progress.setVisibility(View.VISIBLE);
        progressText.setVisibility(View.VISIBLE);
        progressText.setText(text);
    }

    private void clearProgress() {
        progress.setVisibility(View.GONE);
        progressText.setVisibility(View.GONE);
        progressText.setText("");
    }

    private String flagEmoji(String countryCode) {
        String code = countryCode == null ? "" : countryCode.trim().toUpperCase(Locale.ROOT);
        if (code.length() != 2)
            return "";
        int first = Character.codePointAt(code, 0) - 'A' + 0x1F1E6;
        int second = Character.codePointAt(code, 1) - 'A' + 0x1F1E6;
        if (first < 0x1F1E6 || first > 0x1F1FF || second < 0x1F1E6 || second > 0x1F1FF)
            return "";
        return new String(Character.toChars(first)) + new String(Character.toChars(second));
    }

    private String maskAccount(String account) {
        if (TextUtils.isEmpty(account))
            return "";
        int visible = Math.min(4, account.length());
        return "**** " + account.substring(account.length() - visible);
    }

    private void showSettingsDialog() {
        LinearLayout form = new LinearLayout(requireContext());
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        form.setPadding(pad, pad / 2, pad, 0);

        String account = manager.getLastMullvadAccount();
        TextView intro = new TextView(requireContext());
        intro.setText(TextUtils.isEmpty(account)
                ? R.string.vpn_mullvad_intro
                : R.string.vpn_mullvad_account_note);
        intro.setTextAppearance(requireContext(), R.style.TextSmall);
        form.addView(intro, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView current = new TextView(requireContext());
        current.setText(TextUtils.isEmpty(account)
                ? getString(R.string.vpn_account_not_set)
                : getString(R.string.vpn_account_number, maskAccount(account)));
        current.setTextAppearance(requireContext(), R.style.TextMedium);
        current.setPadding(0, pad / 2, 0, 0);
        form.addView(current, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        Button show = new Button(requireContext());
        show.setText(R.string.vpn_account_show);
        show.setVisibility(TextUtils.isEmpty(account) ? View.GONE : View.VISIBLE);
        show.setOnClickListener(v -> {
            boolean hidden = show.getText().equals(getString(R.string.vpn_account_show));
            current.setText(getString(R.string.vpn_account_number,
                    hidden ? account : maskAccount(account)));
            show.setText(hidden ? R.string.vpn_account_hide : R.string.vpn_account_show);
        });
        form.addView(show, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        Button change = new Button(requireContext());
        change.setText(R.string.vpn_account_change);
        change.setVisibility(TextUtils.isEmpty(account) ? View.GONE : View.VISIBLE);
        form.addView(change, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        EditText input = new EditText(requireContext());
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint(R.string.vpn_account_hint);
        input.setPadding(0, pad / 2, 0, 0);
        input.setVisibility(TextUtils.isEmpty(account) ? View.VISIBLE : View.GONE);
        form.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        change.setOnClickListener(v -> {
            input.setVisibility(input.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            change.setText(input.getVisibility() == View.VISIBLE
                    ? R.string.vpn_account_cancel
                    : R.string.vpn_account_change);
            if (input.getVisibility() == View.VISIBLE)
                input.requestFocus();
            else
                input.setText("");
        });

        Button getAccount = new Button(requireContext());
        getAccount.setText(R.string.vpn_get_mullvad_account);
        getAccount.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://mullvad.net/en/account/create"))));
        form.addView(getAccount, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.vpn_settings_title)
                .setView(form)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(TextUtils.isEmpty(account)
                        ? R.string.vpn_account_save
                        : android.R.string.ok, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String next = input.getText().toString().trim();
                    if (TextUtils.isEmpty(next)) {
                        if (TextUtils.isEmpty(manager.getLastMullvadAccount()) ||
                                input.getVisibility() == View.VISIBLE) {
                            input.setError(getString(R.string.vpn_account_hint));
                            return;
                        }
                        dialog.dismiss();
                        return;
                    }

                    manager.saveMullvadAccount(next);
                    Toast.makeText(requireContext(), R.string.vpn_account_saved,
                            Toast.LENGTH_LONG).show();
                    if (countryCache.isEmpty())
                        loadCountries(false);
                    refreshUi();
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private class CountryAdapter extends RecyclerView.Adapter<CountryAdapter.ViewHolder> {
        private final List<MullvadProfileGenerator.CountryOption> countries = new ArrayList<>();

        void setCountries(List<MullvadProfileGenerator.CountryOption> next) {
            countries.clear();
            countries.addAll(next);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_vpn_country, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            MullvadProfileGenerator.CountryOption country = countries.get(position);
            WgProfileManager.MullvadCountry active = manager.getActiveMullvadCountry();
            boolean isActive = active != null && country.code.equals(active.code);
            boolean isGenerating = country.code.equals(generatingCountryCode);

            holder.flag.setText(flagEmoji(country.code));
            holder.name.setText(country.name);
            holder.check.setVisibility(isActive ? View.VISIBLE : View.INVISIBLE);
            holder.rowProgress.setVisibility(isGenerating ? View.VISIBLE : View.GONE);
            holder.itemView.setEnabled(TextUtils.isEmpty(generatingCountryCode));
            holder.itemView.setAlpha(TextUtils.isEmpty(generatingCountryCode) || isGenerating ? 1f : 0.5f);
            holder.itemView.setOnClickListener(v -> generateCountry(country));
        }

        @Override
        public int getItemCount() {
            return countries.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView flag;
            TextView name;
            TextView check;
            ProgressBar rowProgress;

            ViewHolder(View itemView) {
                super(itemView);
                flag = itemView.findViewById(R.id.vpnCountryFlag);
                name = itemView.findViewById(R.id.vpnCountryName);
                check = itemView.findViewById(R.id.vpnCountryCheck);
                rowProgress = itemView.findViewById(R.id.vpnCountryProgress);
            }
        }
    }
}
