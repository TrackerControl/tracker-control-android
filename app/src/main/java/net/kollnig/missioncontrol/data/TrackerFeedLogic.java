/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package net.kollnig.missioncontrol.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Pure row construction for the flat per-app tracker feed. */
public final class TrackerFeedLogic {
    public static final int COLLAPSED_COMPANY_ROWS = 10;

    private TrackerFeedLogic() {
    }

    public static final class SectionRow {
        public final TrackerCategory category;

        SectionRow(TrackerCategory category) {
            this.category = category;
        }

        public TrackerCategory getCategory() {
            return category;
        }
    }

    public static final class CompanyRow {
        public final Tracker tracker;
        public final String categoryName;
        public final boolean expanded;

        CompanyRow(Tracker tracker, String categoryName, boolean expanded) {
            this.tracker = tracker;
            this.categoryName = categoryName;
            this.expanded = expanded;
        }

        public Tracker getTracker() {
            return tracker;
        }

        public String getCategoryName() {
            return categoryName;
        }

        public boolean isExpanded() {
            return expanded;
        }
    }

    public static final class ShowMoreRow {
        public final String categoryName;
        public final int hiddenCount;

        ShowMoreRow(String categoryName, int hiddenCount) {
            this.categoryName = categoryName;
            this.hiddenCount = hiddenCount;
        }

        public String getCategoryName() {
            return categoryName;
        }

        public int getHiddenCount() {
            return hiddenCount;
        }
    }

    /**
     * Build the heterogeneous feed rows. The input category list and child
     * lists are not mutated.
     */
    public static List<Object> buildRows(List<TrackerCategory> categories,
            Set<String> expandedCompanyKeys,
            Set<String> expandedSections) {
        List<TrackerCategory> sortedCategories = new ArrayList<>();
        if (categories != null)
            sortedCategories.addAll(categories);

        Collections.sort(sortedCategories, new Comparator<TrackerCategory>() {
            @Override
            public int compare(TrackerCategory left, TrackerCategory right) {
                return Long.compare(value(right.lastSeen), value(left.lastSeen));
            }
        });

        Set<String> expandedCompanies = expandedCompanyKeys == null
                ? Collections.emptySet() : expandedCompanyKeys;
        Set<String> expandedCategoryNames = expandedSections == null
                ? Collections.emptySet() : expandedSections;
        List<Object> rows = new ArrayList<>();

        for (TrackerCategory category : sortedCategories) {
            String categoryName = category.getCategoryName();
            rows.add(new SectionRow(category));

            List<Tracker> children = category.getChildren();
            boolean expandedSection = expandedCategoryNames.contains(categoryName);
            int visibleCount = expandedSection || children.size() <= COLLAPSED_COMPANY_ROWS + 1
                    ? children.size() : COLLAPSED_COMPANY_ROWS;

            for (int index = 0; index < visibleCount; index++) {
                Tracker tracker = children.get(index);
                rows.add(new CompanyRow(tracker, categoryName,
                        expandedCompanies.contains(TrackerBlocklist.getBlockingKey(tracker))));
            }

            // Keep all eleven rows visible; only N > 11 gets a Show more row.
            if (!expandedSection && children.size() > COLLAPSED_COMPANY_ROWS + 1)
                rows.add(new ShowMoreRow(categoryName, children.size() - visibleCount));
        }

        return rows;
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }
}
