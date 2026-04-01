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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class BlockingModeAndroidTest {

    @Test
    public void bundledExcludedAppsAssetContainsRepresentativePackages() throws Exception {
        Set<String> excludedApps = BlockingModeLogic.parseExcludedAppsJson(readExcludedAppsAsset());

        assertTrue(excludedApps.contains("com.android.chrome"));
        assertTrue(excludedApps.contains("com.whatsapp"));
        assertFalse(excludedApps.contains("com.example.app"));
    }

    @Test
    public void syncModeExclusionsUsesRealExcludedAppsAsset() throws Exception {
        Set<String> excludedApps = BlockingModeLogic.parseExcludedAppsJson(readExcludedAppsAsset());
        Map<String, Boolean> applyPrefs = new HashMap<>();
        applyPrefs.put("manual.false", false);

        BlockingModeLogic.ExclusionSyncResult minimalResult = BlockingModeLogic.syncVpnExclusions(
                BlockingMode.MODE_MINIMAL,
                excludedApps,
                applyPrefs,
                Collections.emptySet());

        assertTrue(minimalResult.applyFalsePackages.contains("com.android.chrome"));
        assertFalse(minimalResult.applyRemovals.contains("manual.false"));

        applyPrefs.put("com.android.chrome", false);
        BlockingModeLogic.ExclusionSyncResult standardResult = BlockingModeLogic.syncVpnExclusions(
                BlockingMode.MODE_STANDARD,
                excludedApps,
                applyPrefs,
                Set.of("com.android.chrome"));

        assertTrue(standardResult.applyRemovals.contains("com.android.chrome"));
        assertFalse(standardResult.applyRemovals.contains("manual.false"));
    }

    @Test
    public void explicitVpnInclusionWithRealExcludedAppsStopsAutoManagement() throws Exception {
        Set<String> excludedApps = BlockingModeLogic.parseExcludedAppsJson(readExcludedAppsAsset());
        assertTrue(excludedApps.contains("com.android.chrome"));

        Map<String, Boolean> applyPrefs = new HashMap<>();
        applyPrefs.put("com.android.chrome", true);

        BlockingModeLogic.ExclusionSyncResult result = BlockingModeLogic.syncVpnExclusions(
                BlockingMode.MODE_MINIMAL,
                Set.of("com.android.chrome"),
                applyPrefs,
                Set.of("com.android.chrome"));

        assertTrue(result.applyFalsePackages.isEmpty());
        assertTrue(result.applyRemovals.isEmpty());
        assertFalse(result.autoExcludedApps.contains("com.android.chrome"));
    }

    private static String readExcludedAppsAsset() throws IOException {
        return new String(Files.readAllBytes(assetPath("ddg-excluded-apps.json")), StandardCharsets.UTF_8);
    }

    private static Path assetPath(String assetName) {
        Path moduleRelative = Path.of("src", "main", "assets", assetName);
        if (Files.exists(moduleRelative))
            return moduleRelative;

        return Path.of("app", "src", "main", "assets", assetName);
    }
}
