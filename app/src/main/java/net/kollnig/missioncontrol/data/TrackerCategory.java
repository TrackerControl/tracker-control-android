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

/**
 * Categorises tracker companies into high-level categories
 */
public class TrackerCategory {
    public static final String UNCATEGORISED = "Uncategorised";
    public String category;
    public Long lastSeen;
    private boolean uncertain = false;
    private List<Tracker> children;

    /**
     * Create class to store tracker category
     *
     * @param category Tracker category
     * @param lastSeen Time when company within category was last contacted
     */
    TrackerCategory(String category, long lastSeen) {
        this.category = category;
        this.lastSeen = lastSeen;
    }

    /**
     * Some trackers are uncertain, due to the nature of DNS. This returns information about this.
     *
     * @return Whether it's uncertain a tracker company has been contacted by an app.
     */
    public boolean isUncertain() {
        return uncertain;
    }

    /**
     * Some trackers are uncertain, due to the nature of DNS. This saves information about this.
     *
     * @param uncertain Whether it's uncertain a tracker company has been contacted by an app.
     */
    public void setUncertain(boolean uncertain) {
        this.uncertain = uncertain;
    }

    /**
     * Get a name of tracker category for display in UI
     *
     * @param c Context
     * @return Name of tracker category to be shown in UI
     */
    public String getDisplayName(Context c) {
        switch (category) {
            case "Advertising":
                return c.getString(R.string.tracker_advertising);
            case "Analytics":
                return c.getString(R.string.tracker_analytics);
            case "Content":
            case "Anti-fraud":
                return c.getString(R.string.tracker_content);
            case "Cryptomining":
                return c.getString(R.string.tracker_cryptomining);
            case "FingerprintingGeneral":
            case "FingerprintingInvasive":
                return c.getString(R.string.tracker_fingerprinting);
            case "Social":
                return c.getString(R.string.tracker_social);
            case "Email":
            case "EmailStrict":
            case "EmailAggressive":
                return c.getString(R.string.tracker_email);
            case UNCATEGORISED:
            default:
                return c.getString(R.string.tracker_uncategorised);
        }
    }

    /**
     * Get list of tracker companies that are within this tracker category
     *
     * @return List of tracker companies that are within this tracker category
     */
    public List<Tracker> getChildren() {
        if (this.children == null)
            this.children = new ArrayList<>();

        return this.children;
    }

    /**
     * Get category name that is used internally
     *
     * @return Internally-used tracker category name
     */
    public String getCategoryName() {
        return category;
    }
}
