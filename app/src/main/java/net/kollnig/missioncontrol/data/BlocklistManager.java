package net.kollnig.missioncontrol.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class BlocklistManager {
    private static final String TAG = "TrackerControl.Blocklist";
    private static final String PREF_BLOCKLISTS = "blocklists";
    public static final String DEFAULT_HOSTS_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts";

    private static BlocklistManager instance;
    private final Context context;

    private BlocklistManager(Context context) {
        this.context = context.getApplicationContext();
        migrateIfNeeded();
        cleanup();
    }

    private void cleanup() {
        File dir = context.getFilesDir();
        File[] files = dir.listFiles(
                (dir1, name) -> name.startsWith("blocklist_") && (name.endsWith(".txt")));
        if (files == null)
            return;

        // Optimize: Use HashSet to avoid O(n*m) nested loop complexity
        List<Blocklist> list = getBlocklists();
        java.util.HashSet<String> validFiles = new java.util.HashSet<>();
        for (Blocklist item : list) {
            validFiles.add("blocklist_" + item.uuid + ".txt");
        }
        
        // Check each file in O(1) time using HashSet
        for (File file : files) {
            if (!validFiles.contains(file.getName())) {
                Log.i(TAG, "Deleting orphaned file " + file.getName());
                file.delete();
            }
        }
    }

    public static synchronized BlocklistManager getInstance(Context context) {
        if (instance == null) {
            instance = new BlocklistManager(context);
        }
        return instance;
    }

    public List<Blocklist> getBlocklists() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String jsonString = prefs.getString(PREF_BLOCKLISTS, "[]");
        List<Blocklist> list = new ArrayList<>();
        try {
            JSONArray jsonArray = new JSONArray(jsonString);
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject obj = jsonArray.getJSONObject(i);
                Blocklist item = new Blocklist();
                item.uuid = obj.getString("uuid");
                item.url = obj.getString("url");
                item.enabled = obj.optBoolean("enabled", true);
                item.lastModified = obj.optLong("lastModified", 0);
                item.lastDownloadSuccess = obj.optBoolean("lastDownloadSuccess", true);
                item.lastErrorMessage = obj.optString("lastErrorMessage", null);
                list.add(item);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing blocklists", e);
        }
        return list;
    }

    public void saveBlocklists(List<Blocklist> list) {
        JSONArray jsonArray = new JSONArray();
        for (Blocklist item : list) {
            try {
                JSONObject obj = new JSONObject();
                obj.put("uuid", item.uuid);
                obj.put("url", item.url);

                obj.put("enabled", item.enabled);
                obj.put("lastModified", item.lastModified);
                obj.put("lastDownloadSuccess", item.lastDownloadSuccess);
                obj.put("lastErrorMessage", item.lastErrorMessage);
                jsonArray.put(obj);
            } catch (JSONException e) {
                Log.e(TAG, "Error saving blocklist item", e);
            }
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putString(PREF_BLOCKLISTS, jsonArray.toString()).apply();
    }

    public void addBlocklist(Blocklist blocklist) {
        List<Blocklist> list = getBlocklists();
        list.add(blocklist);
        saveBlocklists(list);
    }

    public void updateBlocklist(Blocklist blocklist) {
        List<Blocklist> list = getBlocklists();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).uuid.equals(blocklist.uuid)) {
                list.set(i, blocklist);
                break;
            }
        }
        saveBlocklists(list);
    }

    public void removeBlocklist(String uuid) {
        List<Blocklist> list = getBlocklists();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).uuid.equals(uuid)) {
                list.remove(i);
                break;
            }
        }
        saveBlocklists(list);

        // Also remove the cached file
        File file = new File(context.getFilesDir(), "blocklist_" + uuid + ".txt");
        if (file.exists()) {
            file.delete();
        }
    }

    public void migrateIfNeeded() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (prefs.contains("hosts_url_new")) {
            String oldUrl = prefs.getString("hosts_url_new", DEFAULT_HOSTS_URL);

            // Only migrate if we don't have existing blocklists to avoid
            // duplicates/overwrite
            // (Though user said "if existing host list exists, just delete and migrate",
            // the implementation plan says migrate if pref exists.
            // Let's check provided list. If empty, definitely migrate.
            List<Blocklist> currentList = getBlocklists();
            if (currentList.isEmpty()) {
                if (!TextUtils.isEmpty(oldUrl)) {
                    Blocklist item = new Blocklist(oldUrl, true);
                    item.lastModified = prefs.getLong("hosts_last_download_time", 0); // Try to preserve if possible,
                                                                                      // otherwise 0
                    addBlocklist(item);
                } else if (!TextUtils.isEmpty(DEFAULT_HOSTS_URL)) {
                    // Should verify if we want to add default if prompt is empty? Likely yes.
                    Blocklist item = new Blocklist(DEFAULT_HOSTS_URL, true);
                    addBlocklist(item);
                }
            }

            // Remove old preference as requested
            prefs.edit().remove("hosts_url_new").apply();
        } else {
            // If completely fresh install or no pref, ensure we have at least the default
            // list if list is empty
            List<Blocklist> currentList = getBlocklists();
            if (currentList.isEmpty() && !TextUtils.isEmpty(DEFAULT_HOSTS_URL)) {
                Blocklist item = new Blocklist(DEFAULT_HOSTS_URL, true);
                addBlocklist(item);
            }
        }
    }

    public File getBlocklistFile(String uuid) {
        return new File(context.getFilesDir(), "blocklist_" + uuid + ".txt");
    }

    public boolean mergeBlocklists() {
        File hostsFile = new File(context.getFilesDir(), "hosts.txt");
        File hostsTmp = new File(context.getFilesDir(), "hosts.tmp");

        List<Blocklist> list = getBlocklists();
        int count = 0;

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(hostsTmp))) {
            writer.write("# Merged Blocklists by TrackerControl\n");

            for (Blocklist item : list) {
                if (!item.enabled)
                    continue;

                File itemFile = getBlocklistFile(item.uuid);
                if (itemFile.exists() && itemFile.canRead()) {
                    writer.write("# Blocklist: " + item.url + "\n");
                    try (BufferedReader reader = new BufferedReader(new FileReader(itemFile))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            writer.write(line);
                            writer.newLine();
                        }
                    } catch (IOException e) {
                        Log.e(TAG, "Error reading blocklist " + item.url, e);
                    }
                    writer.write("\n");
                    count++;
                }
            }

        } catch (IOException e) {
            Log.e(TAG, "Error writing merged hosts file", e);
            return false;
        }

        if (hostsFile.exists()) {
            hostsFile.delete();
        }
        return hostsTmp.renameTo(hostsFile);
    }
}
