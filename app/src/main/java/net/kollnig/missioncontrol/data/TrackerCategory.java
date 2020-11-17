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
 * Copyright © 2019–2020 Konrad Kollnig (University of Oxford)
 */

package net.kollnig.missioncontrol.data;

import android.content.Context;

import net.kollnig.missioncontrol.R;

import java.util.ArrayList;
import java.util.List;

public class TrackerCategory {
    public String category;
    public Long lastSeen;
    private List<Tracker> children;

    TrackerCategory(String category, long lastSeen) {
        this.category = category;
        this.lastSeen = lastSeen;
    }

    public String getDisplayName(Context c) {
        switch (category) {
            case "Advertising":
                return c.getString(R.string.tracker_advertising);
            case "Analytics":
                return c.getString(R.string.tracker_analytics);
            case "Content":
                return c.getString(R.string.tracker_content);
            case "Cryptomining":
                return c.getString(R.string.tracker_cryptomining);
            case "FingerprintingGeneral":
            case "FingerprintingInvasive":
                return c.getString(R.string.tracker_fingerprinting);
            case "Social":
                return c.getString(R.string.tracker_social);
            default:
                return c.getString(R.string.tracker_uncategorised);
        }
    }

    public List<Tracker> getChildren() {
        if (this.children == null)
            this.children = new ArrayList<>();

        return this.children;
    }

    public String getCategoryName() {
        return category;
    }
}
