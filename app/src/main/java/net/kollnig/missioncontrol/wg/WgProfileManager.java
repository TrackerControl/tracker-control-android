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

        public Profile(String id, String name, String config) {
            this(id, name, config, "", "");
        }

        public Profile(String id, String name, String config, String provider, String account) {
            this.id = id;
            this.name = name;
            this.config = config;
            this.provider = provider;
            this.account = account;
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
            editor.apply();
        } catch (JSONException ex) {
            Log.w(TAG, "Update WireGuard profile failed: " + ex.getMessage());
        }
    }

    public String getProfileSummary(Profile profile) {
        if (profile == null || TextUtils.isEmpty(profile.config))
            return "";

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
                profile.optString("account"));
    }

    private JSONObject toJson(Profile profile) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", profile.id);
        json.put("name", profile.name);
        json.put("config", profile.config);
        json.put("provider", profile.provider);
        json.put("account", profile.account);
        return json;
    }

    private String newId() {
        return "wg-" + System.currentTimeMillis();
    }
}
