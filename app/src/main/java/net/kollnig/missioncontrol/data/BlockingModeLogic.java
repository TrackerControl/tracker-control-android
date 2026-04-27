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
 * Copyright © 2026
 */

package net.kollnig.missioncontrol.data;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure helpers for mode-specific blocking behavior and VPN exclusion syncing.
 */
public final class BlockingModeLogic {
    public static final String MODE_MINIMAL = "minimal";
    public static final String MODE_STANDARD = "standard";
    public static final String MODE_STRICT = "strict";
    public static final String CONTENT_CATEGORY = "Content";

    private BlockingModeLogic() {
    }

    public static boolean blocksAmbiguousTrackerIp(String mode) {
        return MODE_STRICT.equals(mode);
    }

    public static boolean shouldBlockKnownTracker(String mode,
            String trackerCategory,
            boolean blockedByGranularRule) {
        if (MODE_MINIMAL.equals(mode))
            return !CONTENT_CATEGORY.equals(trackerCategory);

        return blockedByGranularRule;
    }

    public static ExclusionSyncResult syncVpnExclusions(String mode,
            Set<String> excludedApps,
            Map<String, Boolean> applyPrefs,
            Set<String> autoExcludedApps) {
        Set<String> nextAutoExcludedApps = new HashSet<>(autoExcludedApps);
        Set<String> applyFalsePackages = new HashSet<>();
        Set<String> applyRemovals = new HashSet<>();

        if (MODE_MINIMAL.equals(mode)) {
            for (String packageName : new HashSet<>(nextAutoExcludedApps)) {
                if (!excludedApps.contains(packageName)) {
                    if (Boolean.FALSE.equals(applyPrefs.get(packageName)))
                        applyRemovals.add(packageName);
                    nextAutoExcludedApps.remove(packageName);
                }
            }

            for (String packageName : excludedApps) {
                if (!applyPrefs.containsKey(packageName)) {
                    applyFalsePackages.add(packageName);
                    nextAutoExcludedApps.add(packageName);
                } else if (Boolean.TRUE.equals(applyPrefs.get(packageName))) {
                    nextAutoExcludedApps.remove(packageName);
                }
            }
        } else {
            for (String packageName : nextAutoExcludedApps)
                if (Boolean.FALSE.equals(applyPrefs.get(packageName)))
                    applyRemovals.add(packageName);
            nextAutoExcludedApps.clear();
        }

        return new ExclusionSyncResult(nextAutoExcludedApps, applyFalsePackages, applyRemovals);
    }

    public static Set<String> clearAutoExcludedApp(Set<String> autoExcludedApps, String packageName) {
        Set<String> nextAutoExcludedApps = new HashSet<>(autoExcludedApps);
        nextAutoExcludedApps.remove(packageName);
        return nextAutoExcludedApps;
    }

    static Set<String> parseExcludedAppsJson(String json) {
        return parseCategories(json, new String[] { "system_ims", "vpn_incompatible", "user_reported" });
    }

    static Set<String> parseBrowserAppsJson(String json) {
        return parseCategories(json, new String[] { "browsers" });
    }

    private static Set<String> parseCategories(String json, String[] categories) {
        Set<String> apps = new HashSet<>();
        for (String category : categories) {
            Matcher categoryMatcher = Pattern.compile(
                    "\"" + Pattern.quote(category) + "\"\\s*:\\s*\\[(.*?)]",
                    Pattern.DOTALL)
                    .matcher(json);
            if (!categoryMatcher.find())
                continue;

            Matcher valueMatcher = Pattern.compile("\"([^\"]+)\"").matcher(categoryMatcher.group(1));
            while (valueMatcher.find())
                apps.add(valueMatcher.group(1));
        }

        return apps;
    }

    public static final class ExclusionSyncResult {
        public final Set<String> autoExcludedApps;
        public final Set<String> applyFalsePackages;
        public final Set<String> applyRemovals;

        ExclusionSyncResult(Set<String> autoExcludedApps,
                Set<String> applyFalsePackages,
                Set<String> applyRemovals) {
            this.autoExcludedApps = autoExcludedApps;
            this.applyFalsePackages = applyFalsePackages;
            this.applyRemovals = applyRemovals;
        }
    }
}
