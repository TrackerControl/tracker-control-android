package net.kollnig.missioncontrol.analysis;

import android.content.Context;
import android.util.Log;

import net.kollnig.missioncontrol.data.ExodusTracker;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Manages the fetch and cache of tracker signatures from Exodus Privacy.
 */
public class TrackerSignatureManager {
    private static final String TAG = "TrackerSignatureManager";
    private static final String EXODUS_URL = "https://reports.exodus-privacy.eu.org/api/trackers";
    private static final String CACHE_FILE = "trackers.json";

    private final Context context;
    private final OkHttpClient client;

    public TrackerSignatureManager(Context context) {
        this.context = context.getApplicationContext();
        this.client = new OkHttpClient();
    }

    /**
     * Downloads the latest signatures from Exodus API and caches them.
     * Use this method in a background thread.
     *
     * @return true if signatures were updated (changed), false otherwise.
     */
    public boolean updateSignatures() {
        Request request = new Request.Builder()
                .url(EXODUS_URL)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                Log.e(TAG, "Failed to download signatures: " + response.code());
                return false;
            }

            String json = response.body().string();

            // Validate JSON before saving
            JSONObject root = new JSONObject(json);
            if (root.has("trackers")) {
                // Check if content changed
                File file = new File(context.getFilesDir(), CACHE_FILE);
                if (file.exists()) {
                    try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                        String cachedJson = readAll(reader);
                        if (json.equals(cachedJson)) {
                            Log.i(TAG, "Signatures are up to date.");
                            return false;
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Could not read existing cache for comparison: " + e.getMessage());
                    }
                }

                saveToCache(json);
                Log.i(TAG, "Successfully updated signatures.");
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating signatures", e);
        }
        return false;
    }

    private void saveToCache(String json) {
        File file = new File(context.getFilesDir(), CACHE_FILE);
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(json);
        } catch (IOException e) {
            Log.e(TAG, "Failed to cache signatures", e);
        }
    }

    /**
     * Gets the list of trackers.
     * Tries to read from cache first. If cache is missing or invalid, falls back to
     * bundled resources.
     */
    public List<ExodusTracker> getTrackers() {
        List<ExodusTracker> cached = loadFromCache();
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        return loadFromAssets();
    }

    private List<ExodusTracker> loadFromCache() {
        File file = new File(context.getFilesDir(), CACHE_FILE);
        if (!file.exists()) {
            return null;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            return parseTrackers(readAll(reader));
        } catch (Exception e) {
            Log.e(TAG, "Failed to load signatures from cache", e);
        }
        return null;
    }

    private List<ExodusTracker> loadFromAssets() {
        Log.i(TAG, "Loading signatures from assets (fallback)");
        try (BufferedReader reader = new BufferedReader(
                new java.io.InputStreamReader(context.getAssets().open("trackers.json")))) {
            return parseTrackers(readAll(reader));
        } catch (IOException e) {
            Log.e(TAG, "Failed to load signatures from assets", e);
        }
        return Collections.emptyList();
    }

    private List<ExodusTracker> parseTrackers(String jsonText) {
        try {
            JSONObject root = new JSONObject(jsonText);
            if (root.has("trackers")) {
                JSONObject trackersNode = root.getJSONObject("trackers");
                List<ExodusTracker> list = new ArrayList<>();
                Iterator<String> keys = trackersNode.keys();

                while (keys.hasNext()) {
                    String key = keys.next();
                    JSONObject obj = trackersNode.getJSONObject(key);

                    ExodusTracker t = new ExodusTracker();
                    t.id = obj.optInt("id");
                    t.name = obj.optString("name");
                    t.website = obj.optString("website");
                    t.codeSignature = obj.optString("code_signature").isEmpty() ? null
                            : obj.getString("code_signature");
                    t.networkSignature = obj.optString("network_signature");

                    // Filter out "good" trackers
                    if (t.name != null && (t.name.equals("Acrarium") || t.name.equals("ACRA")
                            || t.name.equals("Custom Activity On Crash"))) {
                        continue;
                    }

                    list.add(t);
                }
                return list;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing trackers JSON", e);
        }
        return null;
    }

    private String readAll(Reader rd) throws IOException {
        StringBuilder sb = new StringBuilder();
        int cp;
        while ((cp = rd.read()) != -1) {
            sb.append((char) cp);
        }
        return sb.toString();
    }
}
