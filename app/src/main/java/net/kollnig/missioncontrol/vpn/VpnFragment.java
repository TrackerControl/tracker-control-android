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
import android.widget.ImageButton;
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

import com.google.android.material.button.MaterialButtonToggleGroup;
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

import eu.faircode.netguard.ActivitySettings;
import eu.faircode.netguard.ServiceSinkhole;

public class VpnFragment extends Fragment implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String PREF_VPN_MODE = "vpn_mode";
    private static final String MODE_MULLVAD = "mullvad";
    private static final String MODE_WIREGUARD = "wireguard";

    private SharedPreferences prefs;
    private WgProfileManager manager;
    private VpnAdapter adapter;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<MullvadProfileGenerator.CountryOption> countryCache = new ArrayList<>();

    private boolean loadingCountries;
    private boolean progressVisible;
    private boolean countryErrorVisible;
    private String progressMessage = "";
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

        RecyclerView list = view.findViewById(R.id.vpnList);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new VpnAdapter();
        list.setAdapter(adapter);

        refreshUi();
        if (!isFirstTimeVpnSetup() && !isWireGuardMode())
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
                PREF_VPN_MODE.equals(key) ||
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
        countryErrorVisible = false;
        setProgress(getString(R.string.vpn_loading_countries));

        executor.execute(() -> {
            try {
                List<MullvadProfileGenerator.CountryOption> countries =
                        new MullvadProfileGenerator().fetchCountryOptions();
                mainHandler.post(() -> {
                    if (!isAdded())
                        return;
                    loadingCountries = false;
                    countryErrorVisible = false;
                    clearProgress();
                    countryCache.clear();
                    countryCache.addAll(countries);
                    adapter.setCountries(countryCache);
                });
            } catch (Throwable ex) {
                mainHandler.post(() -> {
                    if (!isAdded())
                        return;
                    loadingCountries = false;
                    countryErrorVisible = true;
                    clearProgress();
                    adapter.setCountries(savedMullvadCountries());
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

    private List<WgProfileManager.Profile> customProfiles() {
        List<WgProfileManager.Profile> profiles = new ArrayList<>();
        for (WgProfileManager.Profile profile : manager.getProfiles())
            if (!"mullvad".equals(profile.provider))
                profiles.add(profile);
        return profiles;
    }

    private boolean isWireGuardMode() {
        String mode = prefs.getString(PREF_VPN_MODE, "");
        if (!TextUtils.isEmpty(mode))
            return MODE_WIREGUARD.equals(mode);

        WgProfileManager.Profile active = manager.getActiveProfile();
        if (active != null)
            return !"mullvad".equals(active.provider);
        return !customProfiles().isEmpty() && !hasMullvadSetup();
    }

    private boolean isFirstTimeVpnSetup() {
        return customProfiles().isEmpty() && !hasMullvadSetup();
    }

    private boolean hasMullvadSetup() {
        if (!TextUtils.isEmpty(prefs.getString(WgProfileManager.PREF_MULLVAD_ACCOUNT, "")))
            return true;
        for (WgProfileManager.Profile profile : manager.getProfiles())
            if ("mullvad".equals(profile.provider) &&
                    (!TextUtils.isEmpty(profile.account) ||
                            !TextUtils.isEmpty(profile.countryCode) ||
                            !TextUtils.isEmpty(profile.config)))
                return true;
        return false;
    }

    private void openMullvadSetup() {
        prefs.edit().putString(PREF_VPN_MODE, MODE_MULLVAD).apply();
        showSettingsDialog();
    }

    private void openMullvadAccountPage() {
        startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://mullvad.net/en/account/create")));
    }

    private void openWireGuardProfiles() {
        prefs.edit().putString(PREF_VPN_MODE, MODE_WIREGUARD).apply();
        startActivity(new Intent(requireContext(), ActivityWireGuardProfiles.class));
    }

    private void setWireGuardMode(boolean wireGuard) {
        prefs.edit().putString(PREF_VPN_MODE, wireGuard ? MODE_WIREGUARD : MODE_MULLVAD).apply();
        if (wireGuard) {
            countryErrorVisible = false;
            if (loadingCountries)
                clearProgress();
        } else {
            loadCountries(false);
        }
        refreshUi();
    }

    private void activateCustomProfile(WgProfileManager.Profile profile) {
        manager.setActiveProfile(profile.id);
        prefs.edit()
                .putBoolean("wg_enabled", true)
                .putString(PREF_VPN_MODE, MODE_WIREGUARD)
                .apply();
        ServiceSinkhole.reload("wireguard profile changed", requireContext(), false);
        refreshUi();
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
                    refreshUi();
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
            prefs.edit()
                    .putBoolean("wg_enabled", true)
                    .putString(PREF_VPN_MODE, MODE_MULLVAD)
                    .apply();
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
        if (!isAdded() || adapter == null)
            return;
        adapter.notifyDataSetChanged();
    }

    private void setProgress(String text) {
        progressVisible = true;
        progressMessage = text;
        refreshUi();
    }

    private void clearProgress() {
        progressVisible = false;
        progressMessage = "";
        refreshUi();
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

    private class VpnAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TYPE_STATUS = 0;
        private static final int TYPE_INTRO = 1;
        private static final int TYPE_MODE = 2;
        private static final int TYPE_PROGRESS = 3;
        private static final int TYPE_SECTION = 4;
        private static final int TYPE_ERROR = 5;
        private static final int TYPE_COUNTRY = 6;
        private static final int TYPE_CUSTOM_PROFILE = 7;
        private static final int TYPE_EMPTY = 8;
        private static final int TYPE_FOOTER = 9;

        private final List<MullvadProfileGenerator.CountryOption> countries = new ArrayList<>();

        void setCountries(List<MullvadProfileGenerator.CountryOption> next) {
            countries.clear();
            countries.addAll(next);
            notifyDataSetChanged();
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0)
                return TYPE_STATUS;
            position--;

            if (isFirstTimeVpnSetup())
                return TYPE_INTRO;

            if (position == 0)
                return TYPE_MODE;
            position--;

            if (progressVisible) {
                if (position == 0)
                    return TYPE_PROGRESS;
                position--;
            }

            if (position == 0)
                return TYPE_SECTION;
            position--;

            if (!isWireGuardMode() && countryErrorVisible) {
                if (position == 0)
                    return TYPE_ERROR;
                position--;
            }

            if (isWireGuardMode()) {
                int customCount = customProfiles().size();
                if (customCount == 0 && position == 0)
                    return TYPE_EMPTY;
                if (position < customCount)
                    return TYPE_CUSTOM_PROFILE;
            } else if (position < countries.size()) {
                return TYPE_COUNTRY;
            }
            return TYPE_FOOTER;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_STATUS)
                return new StatusViewHolder(inflater.inflate(R.layout.item_vpn_status, parent, false));
            if (viewType == TYPE_INTRO)
                return new IntroViewHolder(inflater.inflate(R.layout.item_vpn_intro, parent, false));
            if (viewType == TYPE_MODE)
                return new ModeViewHolder(inflater.inflate(R.layout.item_vpn_mode, parent, false));
            if (viewType == TYPE_PROGRESS)
                return new ProgressViewHolder(inflater.inflate(R.layout.item_vpn_progress, parent, false));
            if (viewType == TYPE_SECTION)
                return new SectionViewHolder(inflater.inflate(R.layout.item_vpn_section, parent, false));
            if (viewType == TYPE_ERROR)
                return new ErrorViewHolder(inflater.inflate(R.layout.item_vpn_error, parent, false));
            if (viewType == TYPE_EMPTY)
                return new RecyclerView.ViewHolder(inflater.inflate(R.layout.item_vpn_empty, parent, false)) {};
            if (viewType == TYPE_FOOTER)
                return new FooterViewHolder(inflater.inflate(R.layout.item_vpn_footer, parent, false));
            if (viewType == TYPE_CUSTOM_PROFILE)
                return new CustomProfileViewHolder(inflater.inflate(
                        R.layout.item_vpn_custom_profile, parent, false));
            return new CountryViewHolder(inflater.inflate(R.layout.item_vpn_country, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            int viewType = getItemViewType(position);
            if (viewType == TYPE_STATUS)
                bindStatus((StatusViewHolder) holder);
            else if (viewType == TYPE_INTRO)
                bindIntro((IntroViewHolder) holder);
            else if (viewType == TYPE_MODE)
                bindMode((ModeViewHolder) holder);
            else if (viewType == TYPE_PROGRESS)
                ((ProgressViewHolder) holder).text.setText(progressMessage);
            else if (viewType == TYPE_SECTION)
                ((SectionViewHolder) holder).title.setText(isWireGuardMode()
                        ? R.string.vpn_wireguard_profiles
                        : R.string.vpn_choose_country);
            else if (viewType == TYPE_ERROR)
                ((ErrorViewHolder) holder).retry.setOnClickListener(v -> loadCountries(true));
            else if (viewType == TYPE_FOOTER)
                bindFooter((FooterViewHolder) holder);
            else if (viewType == TYPE_CUSTOM_PROFILE)
                bindCustomProfile((CustomProfileViewHolder) holder,
                        customProfiles().get(position - listStartPosition()));
            else if (viewType == TYPE_COUNTRY)
                bindCountry((CountryViewHolder) holder, countries.get(position - listStartPosition()));
        }

        @Override
        public int getItemCount() {
            if (isFirstTimeVpnSetup())
                return 2;

            int count = listStartPosition();
            if (isWireGuardMode())
                count += Math.max(1, customProfiles().size());
            else
                count += countries.size();
            return isWireGuardMode() ? count + 1 : count;
        }

        private void bindIntro(IntroViewHolder holder) {
            holder.mullvad.setOnClickListener(v -> openMullvadSetup());
            holder.mullvadAccount.setOnClickListener(v -> openMullvadAccountPage());
            holder.wireGuard.setOnClickListener(v -> openWireGuardProfiles());
        }

        private int listStartPosition() {
            int position = 2;
            if (progressVisible)
                position++;
            position++;
            if (!isWireGuardMode() && countryErrorVisible)
                position++;
            return position;
        }

        private void bindMode(ModeViewHolder holder) {
            holder.toggle.clearOnButtonCheckedListeners();
            holder.toggle.check(isWireGuardMode() ? R.id.vpnModeWireGuard : R.id.vpnModeMullvad);
            holder.toggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
                if (!isChecked)
                    return;
                setWireGuardMode(checkedId == R.id.vpnModeWireGuard);
            });
        }

        private void bindStatus(StatusViewHolder holder) {
            boolean enabled = prefs.getBoolean("wg_enabled", false);
            WgProfileManager.Profile active = manager.getActiveProfile();
            boolean activeMullvad = active != null && "mullvad".equals(active.provider);
            String countryName = activeMullvad ? active.countryName : "";
            String countryCode = activeMullvad ? active.countryCode : "";

            holder.switchView.setOnCheckedChangeListener(null);
            holder.switchView.setChecked(enabled);
            holder.switchView.setOnCheckedChangeListener((buttonView, checked) -> {
                if (checked && manager.getActiveProfile() == null) {
                    holder.switchView.setChecked(false);
                    Toast.makeText(requireContext(), R.string.vpn_choose_country_first,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                prefs.edit().putBoolean("wg_enabled", checked).apply();
                ServiceSinkhole.reload("changed wg_enabled", requireContext(), false);
                refreshUi();
            });

            holder.settings.setVisibility(isFirstTimeVpnSetup() ? View.GONE : View.VISIBLE);
            if (!isFirstTimeVpnSetup()) {
                holder.settings.setContentDescription(getString(isWireGuardMode()
                        ? R.string.menu_settings
                        : R.string.vpn_settings));
                holder.settings.setOnClickListener(v -> {
                    if (isWireGuardMode())
                        startActivity(new Intent(requireContext(), ActivitySettings.class));
                    else
                        showSettingsDialog();
                });
            } else {
                holder.settings.setOnClickListener(null);
            }

            if (enabled && activeMullvad && !TextUtils.isEmpty(countryName)) {
                holder.flag.setText(flagEmoji(countryCode));
                holder.flag.setVisibility(View.VISIBLE);
                holder.title.setText(countryName);
                String summary = manager.getProfileSummary(active);
                holder.summary.setText(TextUtils.isEmpty(summary)
                        ? getString(R.string.vpn_status_mullvad)
                        : getString(R.string.vpn_status_relay, summary));
                holder.summary.setVisibility(TextUtils.isEmpty(holder.summary.getText()) ?
                        View.GONE : View.VISIBLE);
            } else if (enabled && active != null) {
                holder.flag.setText("");
                holder.flag.setVisibility(View.GONE);
                holder.title.setText(active.name);
                String summary = manager.getProfileSummary(active);
                holder.summary.setText(TextUtils.isEmpty(summary)
                        ? getString(R.string.vpn_status_wireguard_connected)
                        : summary);
                holder.summary.setVisibility(TextUtils.isEmpty(holder.summary.getText()) ?
                        View.GONE : View.VISIBLE);
            } else {
                holder.flag.setText("");
                holder.flag.setVisibility(View.GONE);
                holder.title.setText(R.string.vpn_status_disconnected);
                holder.summary.setText("");
                holder.summary.setVisibility(View.GONE);
            }
        }

        private void bindCountry(CountryViewHolder holder,
                                 MullvadProfileGenerator.CountryOption country) {
            WgProfileManager.MullvadCountry active = manager.getActiveMullvadCountry();
            boolean isActive = active != null && country.code.equals(active.code);
            boolean isGenerating = country.code.equals(generatingCountryCode);

            holder.flag.setText(flagEmoji(country.code));
            holder.name.setText(country.name);
            holder.check.setVisibility(isActive ? View.VISIBLE : View.INVISIBLE);
            holder.rowProgress.setVisibility(isGenerating ? View.VISIBLE : View.GONE);
            holder.itemView.setEnabled(TextUtils.isEmpty(generatingCountryCode));
            holder.itemView.setAlpha(TextUtils.isEmpty(generatingCountryCode) || isGenerating ?
                    1f : 0.5f);
            holder.itemView.setOnClickListener(v -> generateCountry(country));
        }

        private void bindCustomProfile(CustomProfileViewHolder holder,
                                       WgProfileManager.Profile profile) {
            String activeId = manager.getActiveProfileId();
            String summary = manager.getProfileSummary(profile);
            holder.name.setText(profile.name);
            holder.summary.setText(summary);
            holder.summary.setVisibility(TextUtils.isEmpty(summary) ? View.GONE : View.VISIBLE);
            holder.check.setVisibility(profile.id.equals(activeId) ? View.VISIBLE : View.INVISIBLE);
            holder.itemView.setEnabled(TextUtils.isEmpty(generatingCountryCode));
            holder.itemView.setAlpha(TextUtils.isEmpty(generatingCountryCode) ? 1f : 0.5f);
            holder.itemView.setOnClickListener(v -> activateCustomProfile(profile));
        }

        private void bindFooter(FooterViewHolder holder) {
            holder.text.setText(customProfiles().isEmpty()
                    ? R.string.vpn_import_wireguard_profile
                    : R.string.vpn_manage_wireguard_profiles);
            holder.itemView.setOnClickListener(v -> openWireGuardProfiles());
        }
    }

    private static class StatusViewHolder extends RecyclerView.ViewHolder {
        final TextView flag;
        final TextView title;
        final TextView summary;
        final ImageButton settings;
        final MaterialSwitch switchView;

        StatusViewHolder(View itemView) {
            super(itemView);
            flag = itemView.findViewById(R.id.vpnStatusFlag);
            title = itemView.findViewById(R.id.vpnStatusTitle);
            summary = itemView.findViewById(R.id.vpnStatusSummary);
            settings = itemView.findViewById(R.id.vpnSettingsButton);
            switchView = itemView.findViewById(R.id.vpnEnabledSwitch);
        }
    }

    private static class ModeViewHolder extends RecyclerView.ViewHolder {
        final MaterialButtonToggleGroup toggle;

        ModeViewHolder(View itemView) {
            super(itemView);
            toggle = itemView.findViewById(R.id.vpnModeToggle);
        }
    }

    private static class IntroViewHolder extends RecyclerView.ViewHolder {
        final Button mullvad;
        final Button mullvadAccount;
        final Button wireGuard;

        IntroViewHolder(View itemView) {
            super(itemView);
            mullvad = itemView.findViewById(R.id.vpnIntroMullvadAction);
            mullvadAccount = itemView.findViewById(R.id.vpnIntroMullvadAccountAction);
            wireGuard = itemView.findViewById(R.id.vpnIntroWireGuardAction);
        }
    }

    private static class ProgressViewHolder extends RecyclerView.ViewHolder {
        final TextView text;

        ProgressViewHolder(View itemView) {
            super(itemView);
            text = itemView.findViewById(R.id.vpnProgressText);
        }
    }

    private static class SectionViewHolder extends RecyclerView.ViewHolder {
        final TextView title;

        SectionViewHolder(View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.vpnSectionTitle);
        }
    }

    private static class ErrorViewHolder extends RecyclerView.ViewHolder {
        final Button retry;

        ErrorViewHolder(View itemView) {
            super(itemView);
            retry = itemView.findViewById(R.id.vpnRetryCountries);
        }
    }

    private static class CountryViewHolder extends RecyclerView.ViewHolder {
        final TextView flag;
        final TextView name;
        final TextView check;
        final ProgressBar rowProgress;

        CountryViewHolder(View itemView) {
            super(itemView);
            flag = itemView.findViewById(R.id.vpnCountryFlag);
            name = itemView.findViewById(R.id.vpnCountryName);
            check = itemView.findViewById(R.id.vpnCountryCheck);
            rowProgress = itemView.findViewById(R.id.vpnCountryProgress);
        }
    }

    private static class CustomProfileViewHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView summary;
        final TextView check;

        CustomProfileViewHolder(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.vpnProfileName);
            summary = itemView.findViewById(R.id.vpnProfileSummary);
            check = itemView.findViewById(R.id.vpnProfileCheck);
        }
    }

    private static class FooterViewHolder extends RecyclerView.ViewHolder {
        final TextView text;

        FooterViewHolder(View itemView) {
            super(itemView);
            text = itemView.findViewById(R.id.vpnCustomProfiles);
        }
    }
}
