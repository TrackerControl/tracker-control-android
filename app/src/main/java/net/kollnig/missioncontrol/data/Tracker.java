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

import android.text.TextUtils;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import eu.faircode.netguard.Util;

public class Tracker {
    public String name;
    public String category;
    public Boolean necessary;
    public Long lastSeen;
    private Set<String> hosts = new HashSet<>();

    public Tracker(String name, String category, long lastSeen) {
        this.name = name;
        this.category = category;
        this.necessary = false;
        this.lastSeen = lastSeen;
    }

    public Tracker(String name, String category, Boolean necessary) {
        this.name = name;
        this.category = category;
        this.necessary = necessary;
    }

    @Override
    @NonNull
    public String toString() {
        return toString(false);
    }

    public String toString(boolean unblocked) {
        List<String> sortedHosts = getSortedHosts();
        String hosts = "\n• " + TextUtils.join("\n• ", sortedHosts);

        String title;
        if (lastSeen != 0) {
            title = name + "  (" + Util.relativeTime(lastSeen) + ")";
        } else {
            title = name;
        }

        if ((TrackerList.necessaryTrackers.contains(name)
                && !Util.isPlayStoreInstall()) ||
                unblocked)
            return title + " (Unblocked)" + hosts;
        else {
            return title + hosts;
        }
    }

    public String getName() {
        return name;
    }

    public String getCategory() {
        if (category == null || category.equals("null")) return null;
        return category;
    }

    void addHost(String host) {
        this.hosts.add(host);
    }

    private List<String> getSortedHosts() {
        List<String> list = new ArrayList<>(hosts);
        java.util.Collections.sort(list);
        return list;
    }
}
