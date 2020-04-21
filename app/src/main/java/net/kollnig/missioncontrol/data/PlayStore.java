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
 * along with TrackerControl.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2019 Konrad Kollnig, University of Oxford
 */

package net.kollnig.missioncontrol.data;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.kollnig.missioncontrol.Common.fetch;

public class PlayStore {
    private static final String PLAYSTORE_BASE = "https://play.google.com/store/apps/details?id=";

    @Nullable
    public static AppInfo getInfo(String appId) {
        String playStoreInfo = fetch(PLAYSTORE_BASE + appId);
        if (playStoreInfo == null)
            return null;

        return parse(playStoreInfo);
    }

    /**
     * Parsing of PlayStore Info, according to https://github.com/facundoolano/google-play-scraper/
     *
     * @param playStoreInfo Fetched html from PlayStore
     */
    @Nullable
    private static AppInfo parse(String playStoreInfo) {
        String scriptRegex = ">AF_initDataCallback[\\s\\S]*?</script";
        String keyRegex = "(ds:.*?)'";
        String valueRegex = "return ([\\s\\S]*?)\\}\\}\\);<\\/";

        Matcher scriptMatcher = Pattern.compile(scriptRegex)
                .matcher(playStoreInfo);
        while (scriptMatcher.find()) {
            String scriptMatch = scriptMatcher.group();
            Matcher keyMatcher = Pattern.compile(keyRegex).matcher(scriptMatch);
            Matcher valueMatcher = Pattern.compile(valueRegex).matcher(scriptMatch);

            if (keyMatcher.find() && valueMatcher.find()) {
                String key = keyMatcher.group(1);

                // Key taken from source above
                if (key.equals("ds:5")) {
                    String json = valueMatcher.group(1);

                    JSONArray data;
                    try {
                        data = new JSONArray(json);
                    } catch (JSONException e) {
                        return null;
                    }

                    String policyUrl, developerMail, summary;
                    try {
                        policyUrl = data.getJSONArray(0).getJSONArray(12).getJSONArray(7).getString(2);
                    } catch (NullPointerException | JSONException e) {
                        policyUrl = null;
                    }

                    try {
                        developerMail = data.getJSONArray(0).getJSONArray(12)
                                .getJSONArray(5).getJSONArray(2).getString(0);
                    } catch (NullPointerException | JSONException e) {
                        developerMail = null;
                    }

                    try {
                        summary = data.getJSONArray(0).getJSONArray(10).getJSONArray(1).getString(1);
                    } catch (NullPointerException | JSONException e) {
                        summary = null;
                    }

                    return new AppInfo(policyUrl, developerMail, summary);
                }
            }
        }

        return null;
    }

    public static class AppInfo {
        public String policyUrl;
        public String developerMail;
        public String summary;

        public AppInfo(String policyUrl, String developerMail, String summary) {
            this.policyUrl = policyUrl;
            this.developerMail = developerMail;
            this.summary = summary;
        }
    }
}
