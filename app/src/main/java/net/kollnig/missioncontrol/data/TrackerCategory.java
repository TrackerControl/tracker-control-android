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

import java.util.ArrayList;
import java.util.List;

public class TrackerCategory {
    public String name;
    private List<Tracker> children;
    public Long lastSeen;

    TrackerCategory(String name, long lastSeen) {
        this.name = name;
        this.lastSeen = lastSeen;
    }

    public List<Tracker> getChildren() {
        if (this.children == null) {
            this.children = new ArrayList<>();
            return this.children;
        } else {
            return this.children;
        }
    }

    public long getLastSeen() {
        return lastSeen;
    }
}
