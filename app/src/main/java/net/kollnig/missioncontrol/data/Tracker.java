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

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Store information about tracker companies, and tracker found in apps' network traffic
 */
public class Tracker {
    private final Set<String> hosts = new HashSet<>();
    private List<String> sortedHostsCache = null; // Cache sorted hosts for performance
    public String name;
    public String category;
    public Long lastSeen;
    public String country;

    /**
     * Creates class for tracker seen in apps' network traffic
     *
     * @param name     Company name of tracker
     * @param category Category of tracker
     * @param lastSeen Time when tracker was last seen in network traffic
     */
    public Tracker(String name, String category, long lastSeen) {
        this.name = name;
        this.category = category;
        this.lastSeen = lastSeen;
    }

    /**
     * Creates class to store information about tracker (not necessarily seen in network traffic)
     *
     * @param name     Company name of tracker
     * @param category Category of tracker
     */
    public Tracker(String name, String category) {
        this.name = name;
        this.category = category;
    }

    @Override
    @NonNull
    public String toString() {
        if (this.name == null)
            return "NO_TRACKER";

        return getName();
    }

    /**
     * Get name of tracker company
     *
     * @return Name of tracker company
     */
    public String getName() {
        if (name != null && name.equals("Alphabet"))
            return "Google";

        if (name != null && name.equals("Adobe Systems"))
            return "Adobe";

        return name;
    }

    /**
     * Get category of tracker
     *
     * @return Category of tracker
     */
    public String getCategory() {
        if (category == null || category.equals("null")) return null;
        return category;
    }

    /**
     * Add observed tracker host
     *
     * @param host Tracker host
     */
    void addHost(String host) {
        this.hosts.add(host);
        // Invalidate cache when host is added
        sortedHostsCache = null;
    }

    /**
     * Get set of hosts that this tracker company has been observed contacting
     *
     * @return
     */
    public Set<String> getHosts() {
        return hosts;
    }

    /**
     * Get sorted list of hosts. This method caches the sorted result for performance.
     * Calling this repeatedly is more efficient than sorting in UI code.
     *
     * @return Sorted list of hosts
     */
    public List<String> getSortedHosts() {
        if (sortedHostsCache == null) {
            sortedHostsCache = new ArrayList<>(hosts);
            Collections.sort(sortedHostsCache);
        }
        return sortedHostsCache;
    }
}
