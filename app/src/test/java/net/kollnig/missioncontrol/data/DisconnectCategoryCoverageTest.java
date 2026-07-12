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
 * along with TrackerControl. If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright © 2026
 */

package net.kollnig.missioncontrol.data;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Flags when the bundled Disconnect.me list introduces a tracker category that
 * TrackerControl cannot place into a real UI bucket.
 *
 * <p>Disconnect occasionally renames or adds categories. When that happens the
 * app does not crash — the category simply folds into "Uncategorised" via
 * {@link TrackerCategory#canonicalise} — but that is a silent loss of signal
 * nobody would notice. This test turns such drift into a build failure so a
 * maintainer can add an alias in {@code TrackerCategory.DISCONNECT_ALIASES} or a
 * new bucket in {@code TrackerCategory.LABELS} (plus a string resource).
 *
 * <p>Runs on every unit-test build, so it guards <em>any</em> update to the
 * bundled asset — the automated {@code update-disconnect.sh} refresh, a manual
 * edit, or a future change of update mechanism.
 */
public class DisconnectCategoryCoverageTest {

    // A Disconnect category is the only place a bare "Name": [ ... ] array key
    // appears: tracker names are followed by objects and domain keys are URLs,
    // so neither matches this pattern. Verified against jq's parse of the list.
    private static final Pattern CATEGORY_KEY =
            Pattern.compile("\"([A-Za-z][A-Za-z0-9 -]*)\"\\s*:\\s*\\[");

    @Test
    public void everyBundledDisconnectCategoryMapsToAKnownBucket() throws IOException {
        Set<String> categories = bundledDisconnectCategories();

        assertTrue("Could not extract any categories from the Disconnect asset — "
                + "did its format change?", categories.size() >= 5);

        List<String> unrecognised = new ArrayList<>();
        for (String category : categories) {
            if (!TrackerCategory.isRecognisedDisconnectCategory(category))
                unrecognised.add(category);
        }

        assertTrue("Disconnect.me introduced categories the app can't place: " + unrecognised
                + ". Add an alias in TrackerCategory.DISCONNECT_ALIASES or a bucket in "
                + "TrackerCategory.LABELS (+ a string resource) so they stop folding "
                + "silently into Uncategorised.", unrecognised.isEmpty());
    }

    private static Set<String> bundledDisconnectCategories() throws IOException {
        String reversed = new String(
                Files.readAllBytes(assetPath("disconnect-blacklist.reversed.json")),
                StandardCharsets.UTF_8);
        String json = new StringBuilder(reversed).reverse().toString();

        // Scan only from the "categories" object so prose in the license field
        // can never produce a false positive.
        int start = json.indexOf("\"categories\"");
        Matcher m = CATEGORY_KEY.matcher(start >= 0 ? json.substring(start) : json);

        Set<String> categories = new LinkedHashSet<>();
        while (m.find())
            categories.add(m.group(1));
        return categories;
    }

    private static Path assetPath(String assetName) {
        Path moduleRelative = Path.of("src", "main", "assets", assetName);
        if (Files.exists(moduleRelative))
            return moduleRelative;

        return Path.of("app", "src", "main", "assets", assetName);
    }
}
