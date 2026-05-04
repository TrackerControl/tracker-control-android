package net.kollnig.missioncontrol.wg;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import androidx.preference.PreferenceManager;

import net.kollnig.missioncontrol.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class WgProfileManager {
    private static final String TAG = "TrackerControl.WgProfiles";
    public static final String PREF_WG_PROFILES = "wg_profiles";
    public static final String PREF_WG_PROFILE = "wg_profile";
    public static final String PREF_WG_CONFIG = "wg_config";
    public static final String PREF_MULLVAD_ACCOUNT = "mullvad_account";
    public static final String PREF_MULLVAD_DEVICE_ID = "mullvad_device_id";
    public static final String PREF_IVPN_ACCOUNT = "ivpn_account";
    public static final String PREF_IVPN_SESSION_TOKEN = "ivpn_session_token";
    public static final String PREF_IVPN_PRIVATE_KEY = "ivpn_private_key";
    public static final String PREF_IVPN_PUBLIC_KEY = "ivpn_public_key";
    public static final String PREF_IVPN_ADDRESS = "ivpn_address";

    private final Context context;
    private final SharedPreferences prefs;

    public WgProfileManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = PreferenceManager.getDefaultSharedPreferences(context);
    }

    public static class Profile {
        public final String id;
        public final String name;
        public final String config;
        public final String provider;
        public final String account;
        public final String countryCode;
        public final String countryName;

        public Profile(String id, String name, String config) {
            this(id, name, config, "", "", "", "");
        }

        public Profile(String id, String name, String config, String provider, String account) {
            this(id, name, config, provider, account, "", "");
        }

        public Profile(String id, String name, String config, String provider, String account,
                       String countryCode, String countryName) {
            this.id = id;
            this.name = name;
            this.config = config;
            this.provider = provider;
            this.account = account;
            this.countryCode = normalizeCountry(countryCode);
            this.countryName = countryName == null ? "" : countryName;
        }
    }

    public static class MullvadCountry {
        public final String code;
        public final String name;

        public MullvadCountry(String code, String name) {
            this.code = normalizeCountry(code);
            this.name = name == null ? "" : name;
        }
    }

    public static class IvpnSession {
        public final String token;
        public final String privateKey;
        public final String publicKey;
        public final String address;

        public IvpnSession(String token, String privateKey, String publicKey, String address) {
            this.token = token == null ? "" : token;
            this.privateKey = privateKey == null ? "" : privateKey;
            this.publicKey = publicKey == null ? "" : publicKey;
            this.address = address == null ? "" : address;
        }

        public boolean isUsable() {
            return !TextUtils.isEmpty(token) &&
                    !TextUtils.isEmpty(privateKey) &&
                    !TextUtils.isEmpty(publicKey) &&
                    !TextUtils.isEmpty(address);
        }
    }

    public void migrateIfNeeded() {
        JSONArray profiles = readProfilesJson();
        String active = prefs.getString(PREF_WG_PROFILE, "");
        SharedPreferences.Editor editor = null;

        if (profiles.length() == 0) {
            String config = prefs.getString(PREF_WG_CONFIG, "");
            if (!TextUtils.isEmpty(config)) {
                try {
                    String id = newId();
                    JSONObject profile = toJson(new Profile(
                            id,
                            context.getString(R.string.msg_wg_profile_default_name),
                            config));
                    profiles.put(profile);
                    editor = prefs.edit();
                    writeProfilesJson(editor, profiles);
                    editor.putString(PREF_WG_PROFILE, id);
                } catch (JSONException ex) {
                    Log.e(TAG, "Create default WireGuard profile failed: " + ex.getMessage());
                }
            }
        } else if (findJsonProfile(profiles, active) == null) {
            JSONObject first = profiles.optJSONObject(0);
            if (first != null) {
                editor = prefs.edit();
                editor.putString(PREF_WG_PROFILE, first.optString("id"));
                editor.putString(PREF_WG_CONFIG, first.optString("config", ""));
            }
        }

        if (editor != null)
            editor.apply();
    }

    public List<Profile> getProfiles() {
        JSONArray profiles = readProfilesJson();
        List<Profile> result = new ArrayList<>();
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject profile = profiles.optJSONObject(i);
            if (profile != null)
                result.add(fromJson(profile));
        }
        return result;
    }

    public Profile getActiveProfile() {
        return getProfile(prefs.getString(PREF_WG_PROFILE, ""));
    }

    public String getActiveProfileId() {
        return prefs.getString(PREF_WG_PROFILE, "");
    }

    public Profile getProfile(String id) {
        JSONObject profile = findJsonProfile(readProfilesJson(), id);
        return profile == null ? null : fromJson(profile);
    }

    public void setActiveProfile(String id) {
        Profile profile = getProfile(id);
        if (profile == null)
            return;

        prefs.edit()
                .putString(PREF_WG_PROFILE, profile.id)
                .putString(PREF_WG_CONFIG, profile.config)
                .apply();
    }

    public void saveProfile(String id, String name, String config) throws JSONException {
        saveProfile(id, name, config, "", "");
    }

    public void saveProfile(String id, String name, String config, String provider, String account) throws JSONException {
        saveProfile(id, name, config, provider, account, "", "");
    }

    public void saveProfile(String id, String name, String config, String provider, String account,
                            String countryCode, String countryName) throws JSONException {
        JSONArray profiles = readProfilesJson();
        JSONObject profile = TextUtils.isEmpty(id) ? null : findJsonProfile(profiles, id);
        if (profile == null) {
            profile = new JSONObject();
            profile.put("id", newId());
            profiles.put(profile);
        }

        profile.put("name", name);
        profile.put("config", config);
        profile.put("provider", provider == null ? "" : provider);
        profile.put("account", account == null ? "" : account);
        profile.put("countryCode", normalizeCountry(countryCode));
        profile.put("countryName", countryName == null ? "" : countryName);

        SharedPreferences.Editor editor = prefs.edit();
        writeProfilesJson(editor, profiles);
        editor.putString(PREF_WG_PROFILE, profile.optString("id"));
        editor.putString(PREF_WG_CONFIG, config);
        editor.apply();
    }

    public void deleteProfile(String id) {
        JSONArray profiles = readProfilesJson();
        JSONArray kept = new JSONArray();
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject profile = profiles.optJSONObject(i);
            if (profile != null && !id.equals(profile.optString("id")))
                kept.put(profile);
        }

        SharedPreferences.Editor editor = prefs.edit();
        writeProfilesJson(editor, kept);
        if (kept.length() == 0) {
            editor.remove(PREF_WG_PROFILE);
            editor.remove(PREF_WG_CONFIG);
        } else {
            JSONObject next = kept.optJSONObject(0);
            if (next != null) {
                editor.putString(PREF_WG_PROFILE, next.optString("id"));
                editor.putString(PREF_WG_CONFIG, next.optString("config", ""));
            }
        }
        editor.apply();
    }

    public void updateActiveProfileConfig(String config) {
        String active = getActiveProfileId();
        if (TextUtils.isEmpty(active))
            return;

        JSONArray profiles = readProfilesJson();
        JSONObject profile = findJsonProfile(profiles, active);
        if (profile == null)
            return;

        try {
            profile.put("config", config == null ? "" : config);
            SharedPreferences.Editor editor = prefs.edit();
            writeProfilesJson(editor, profiles);
            editor.putString(PREF_WG_CONFIG, config == null ? "" : config);
            editor.apply();
        } catch (JSONException ex) {
            Log.w(TAG, "Update WireGuard profile failed: " + ex.getMessage());
        }
    }

    public MullvadCountry getActiveMullvadCountry() {
        Profile profile = getActiveProfile();
        if (profile == null || !"mullvad".equals(profile.provider))
            return null;
        if (TextUtils.isEmpty(profile.countryCode) && TextUtils.isEmpty(profile.countryName))
            return null;
        return new MullvadCountry(profile.countryCode, profile.countryName);
    }

    public Profile findMullvadProfileForCountry(String code) {
        return findProfileForProviderCountry("mullvad", code);
    }

    public Profile findIvpnProfileForCountry(String code) {
        return findProfileForProviderCountry("ivpn", code);
    }

    private Profile findProfileForProviderCountry(String provider, String code) {
        String country = normalizeCountry(code);
        if (TextUtils.isEmpty(country))
            return null;
        for (Profile profile : getProfiles())
            if (provider.equals(profile.provider) && country.equals(profile.countryCode))
                return profile;
        return null;
    }

    public String getLastMullvadAccount() {
        String saved = prefs.getString(PREF_MULLVAD_ACCOUNT, "");
        if (!TextUtils.isEmpty(saved))
            return saved;

        Profile active = getActiveProfile();
        if (isMullvadProfileForAccount(active, null)) {
            saveMullvadAccount(active.account);
            return active.account;
        }

        for (Profile profile : getProfiles())
            if (isMullvadProfileForAccount(profile, null)) {
                saveMullvadAccount(profile.account);
                return profile.account;
            }
        return "";
    }

    public void saveMullvadAccount(String accountNumber) {
        String next = accountNumber == null ? "" : accountNumber.trim();
        String current = prefs.getString(PREF_MULLVAD_ACCOUNT, "");
        SharedPreferences.Editor editor = prefs.edit()
                .putString(PREF_MULLVAD_ACCOUNT, next);
        if (!next.equals(current))
            editor.remove(PREF_MULLVAD_DEVICE_ID);
        editor.apply();
    }

    public String getMullvadDeviceId() {
        return prefs.getString(PREF_MULLVAD_DEVICE_ID, "");
    }

    public void saveMullvadDeviceId(String deviceId) {
        if (TextUtils.isEmpty(deviceId))
            return;
        prefs.edit()
                .putString(PREF_MULLVAD_DEVICE_ID, deviceId.trim())
                .apply();
    }

    public String getLastIvpnAccount() {
        String saved = prefs.getString(PREF_IVPN_ACCOUNT, "");
        if (!TextUtils.isEmpty(saved))
            return saved;

        Profile active = getActiveProfile();
        if (isProviderProfileForAccount(active, "ivpn", null)) {
            saveIvpnAccount(active.account);
            return active.account;
        }

        for (Profile profile : getProfiles())
            if (isProviderProfileForAccount(profile, "ivpn", null)) {
                saveIvpnAccount(profile.account);
                return profile.account;
            }
        return "";
    }

    public void saveIvpnAccount(String accountNumber) {
        String next = accountNumber == null ? "" : accountNumber.trim();
        String current = prefs.getString(PREF_IVPN_ACCOUNT, "");
        SharedPreferences.Editor editor = prefs.edit()
                .putString(PREF_IVPN_ACCOUNT, next);
        if (!next.equals(current)) {
            editor.remove(PREF_IVPN_SESSION_TOKEN);
            editor.remove(PREF_IVPN_PRIVATE_KEY);
            editor.remove(PREF_IVPN_PUBLIC_KEY);
            editor.remove(PREF_IVPN_ADDRESS);
        }
        editor.apply();
    }

    public IvpnSession getIvpnSession(String accountNumber) {
        String account = accountNumber == null ? "" : accountNumber.trim();
        if (TextUtils.isEmpty(account) || !account.equals(getLastIvpnAccount()))
            return null;
        IvpnSession session = new IvpnSession(
                prefs.getString(PREF_IVPN_SESSION_TOKEN, ""),
                prefs.getString(PREF_IVPN_PRIVATE_KEY, ""),
                prefs.getString(PREF_IVPN_PUBLIC_KEY, ""),
                prefs.getString(PREF_IVPN_ADDRESS, ""));
        return session.isUsable() ? session : null;
    }

    public void saveIvpnSession(IvpnSession session) {
        if (session == null)
            return;
        prefs.edit()
                .putString(PREF_IVPN_SESSION_TOKEN, session.token)
                .putString(PREF_IVPN_PRIVATE_KEY, session.privateKey)
                .putString(PREF_IVPN_PUBLIC_KEY, session.publicKey)
                .putString(PREF_IVPN_ADDRESS, session.address)
                .apply();
    }

    public String getProviderConfig(String provider, String account) {
        String normalized = account == null ? "" : account.trim();
        Profile active = getActiveProfile();
        if (isProviderProfileForAccount(active, provider, normalized))
            return active.config;

        for (Profile profile : getProfiles())
            if (isProviderProfileForAccount(profile, provider, normalized))
                return profile.config;
        return "";
    }

    public boolean hasProviderProfiles(String provider, String account) {
        return !TextUtils.isEmpty(getProviderConfig(provider, account));
    }

    public boolean rewriteProviderInterface(String provider, String account,
                                            String privateKey, String address) throws JSONException {
        String normalized = account == null ? "" : account.trim();
        if (TextUtils.isEmpty(provider) || TextUtils.isEmpty(normalized) ||
                TextUtils.isEmpty(privateKey))
            return false;

        JSONArray profiles = readProfilesJson();
        String active = getActiveProfileId();
        boolean changed = false;
        boolean activeChanged = false;
        String activeConfig = null;

        for (int i = 0; i < profiles.length(); i++) {
            JSONObject profile = profiles.optJSONObject(i);
            if (profile == null)
                continue;
            if (!provider.equals(profile.optString("provider")) ||
                    !normalized.equals(profile.optString("account")))
                continue;

            String config = profile.optString("config", "");
            String next = replaceInterfaceLine(config, "PrivateKey", privateKey);
            if (!TextUtils.isEmpty(address))
                next = replaceInterfaceLine(next, "Address", address);
            if (!next.equals(config)) {
                profile.put("config", next);
                changed = true;
                if (active.equals(profile.optString("id"))) {
                    activeChanged = true;
                    activeConfig = next;
                }
            }
        }

        if (!changed)
            return false;

        SharedPreferences.Editor editor = prefs.edit();
        writeProfilesJson(editor, profiles);
        if (activeChanged)
            editor.putString(PREF_WG_CONFIG, activeConfig == null ? "" : activeConfig);
        editor.apply();
        return activeChanged;
    }

    public String getReusableMullvadConfig(String accountNumber) {
        String account = accountNumber == null ? "" : accountNumber.trim();
        Profile active = getActiveProfile();
        if (isMullvadProfileForAccount(active, account))
            return active.config;

        for (Profile profile : getProfiles())
            if (isMullvadProfileForAccount(profile, account))
                return profile.config;
        return null;
    }

    private boolean isMullvadProfileForAccount(Profile profile, String account) {
        return isProviderProfileForAccount(profile, "mullvad", account);
    }

    private boolean isProviderProfileForAccount(Profile profile, String provider, String account) {
        if (profile == null ||
                !provider.equals(profile.provider) ||
                TextUtils.isEmpty(profile.account) ||
                TextUtils.isEmpty(profile.config))
            return false;
        return account == null || account.equals(profile.account);
    }

    public String getProfileSummary(Profile profile) {
        if (profile == null || TextUtils.isEmpty(profile.config))
            return "";

        String relay = getRelaySummary(profile.config);
        if (!TextUtils.isEmpty(relay))
            return relay;

        try {
            WgConfig config = WgConfigParser.INSTANCE.parse(profile.config);
            List<WgPeer> peers = config.getPeers();
            if (!peers.isEmpty() && !TextUtils.isEmpty(peers.get(0).getEndpoint()))
                return peers.get(0).getEndpoint();
        } catch (Throwable ignored) {
            // The editor validates configs before saving. This is only display metadata.
        }
        return "";
    }

    private String getRelaySummary(String config) {
        String[] lines = config.split("\\r?\\n");
        for (String line : lines) {
            String mullvad = "# Mullvad relay = ";
            if (line.startsWith(mullvad))
                return line.substring(mullvad.length()).trim();
            String ivpn = "# IVPN relay = ";
            if (line.startsWith(ivpn))
                return line.substring(ivpn.length()).trim();
        }
        return "";
    }

    private JSONArray readProfilesJson() {
        String json = prefs.getString(PREF_WG_PROFILES, "[]");
        try {
            return new JSONArray(json);
        } catch (JSONException ex) {
            Log.w(TAG, "Bad WireGuard profile list, resetting: " + ex.getMessage());
            return new JSONArray();
        }
    }

    private void writeProfilesJson(SharedPreferences.Editor editor, JSONArray profiles) {
        editor.putString(PREF_WG_PROFILES, profiles.toString());
    }

    private JSONObject findJsonProfile(JSONArray profiles, String id) {
        if (TextUtils.isEmpty(id))
            return null;

        for (int i = 0; i < profiles.length(); i++) {
            JSONObject profile = profiles.optJSONObject(i);
            if (profile != null && id.equals(profile.optString("id")))
                return profile;
        }
        return null;
    }

    private Profile fromJson(JSONObject profile) {
        return new Profile(
                profile.optString("id"),
                profile.optString("name"),
                profile.optString("config"),
                profile.optString("provider"),
                profile.optString("account"),
                profile.optString("countryCode"),
                profile.optString("countryName"));
    }

    private JSONObject toJson(Profile profile) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", profile.id);
        json.put("name", profile.name);
        json.put("config", profile.config);
        json.put("provider", profile.provider);
        json.put("account", profile.account);
        json.put("countryCode", profile.countryCode);
        json.put("countryName", profile.countryName);
        return json;
    }

    private static String replaceInterfaceLine(String config, String key, String value) {
        if (TextUtils.isEmpty(config))
            return config == null ? "" : config;

        String[] lines = config.split("\\r?\\n", -1);
        boolean inInterface = false;
        boolean replaced = false;
        int interfaceHeaderEnd = -1;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                inInterface = "[Interface]".equalsIgnoreCase(trimmed);
                if (inInterface)
                    interfaceHeaderEnd = sb.length() + line.length() +
                            (i < lines.length - 1 ? 1 : 0);
            }
            if (inInterface && startsWithKey(line, key)) {
                line = key + " = " + value;
                replaced = true;
            }
            sb.append(line);
            if (i < lines.length - 1)
                sb.append('\n');
        }
        if (!replaced) {
            int insertAt = interfaceHeaderEnd >= 0 ? interfaceHeaderEnd : 0;
            sb.insert(insertAt, key + " = " + value + "\n");
        }
        return sb.toString();
    }

    private static boolean startsWithKey(String line, String key) {
        String trimmed = line.trim();
        int eq = trimmed.indexOf('=');
        if (eq < 0)
            return false;
        return key.equalsIgnoreCase(trimmed.substring(0, eq).trim());
    }

    private String newId() {
        return "wg-" + System.currentTimeMillis();
    }

    private static String normalizeCountry(String countryCode) {
        if (countryCode == null)
            return "";
        return countryCode.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
