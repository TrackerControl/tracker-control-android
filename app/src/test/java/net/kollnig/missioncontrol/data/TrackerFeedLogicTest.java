package net.kollnig.missioncontrol.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

public class TrackerFeedLogicTest {
    @Test
    public void categoriesAreOrderedByRecencyIncludingEssential() {
        TrackerCategory old = category("Advertising", 10, 1);
        TrackerCategory essential = category("Content", 30, 1);
        TrackerCategory newest = category("Analytics", 50, 1);
        List<Object> rows = TrackerFeedLogic.buildRows(Arrays.asList(old, essential, newest),
                null, null);

        assertEquals(newest, ((TrackerFeedLogic.SectionRow) rows.get(0)).category);
        assertEquals(essential, ((TrackerFeedLogic.SectionRow) rows.get(2)).category);
        assertEquals(old, ((TrackerFeedLogic.SectionRow) rows.get(4)).category);
    }

    @Test
    public void elevenCompaniesHaveNoShowMoreButTwelveCollapseToTen() {
        TrackerCategory eleven = category("Advertising", 1, 11);
        List<Object> elevenRows = TrackerFeedLogic.buildRows(Arrays.asList(eleven), null, null);
        assertEquals(12, elevenRows.size());
        assertFalse(elevenRows.get(elevenRows.size() - 1) instanceof TrackerFeedLogic.ShowMoreRow);

        TrackerCategory twelve = category("Analytics", 1, 12);
        List<Object> twelveRows = TrackerFeedLogic.buildRows(Arrays.asList(twelve), null, null);
        assertEquals(12, twelveRows.size());
        assertTrue(twelveRows.get(11) instanceof TrackerFeedLogic.ShowMoreRow);
        assertEquals(2, ((TrackerFeedLogic.ShowMoreRow) twelveRows.get(11)).hiddenCount);
    }

    @Test
    public void expandedSectionShowsAllCompanies() {
        TrackerCategory category = category("Advertising", 1, 12);
        List<Object> rows = TrackerFeedLogic.buildRows(Arrays.asList(category), null,
                new HashSet<>(Arrays.asList("Advertising")));
        assertEquals(13, rows.size());
        assertTrue(rows.get(12) instanceof TrackerFeedLogic.CompanyRow);
    }

    @Test
    public void companyExpansionUsesBlockingKey() {
        TrackerCategory category = category("Advertising", 1, 1);
        Tracker tracker = category.getChildren().get(0);
        List<Object> rows = TrackerFeedLogic.buildRows(Arrays.asList(category),
                new HashSet<>(Arrays.asList(TrackerBlocklist.getBlockingKey(tracker))), null);
        assertTrue(((TrackerFeedLogic.CompanyRow) rows.get(1)).expanded);
    }

    @Test
    public void zeroLastSeenStillEmitsRow() {
        TrackerCategory category = new TrackerCategory("Advertising", 0);
        Tracker tracker = new Tracker("Zero", "Advertising", 0);
        category.getChildren().add(tracker);
        List<Object> rows = TrackerFeedLogic.buildRows(Arrays.asList(category), null, null);
        assertEquals(2, rows.size());
        assertEquals(Long.valueOf(0), category.lastSeen);
    }

    private static TrackerCategory category(String name, long lastSeen, int companyCount) {
        TrackerCategory category = new TrackerCategory(name, lastSeen);
        for (int index = 0; index < companyCount; index++) {
            Tracker tracker = new Tracker(name + index, name, lastSeen - index);
            category.getChildren().add(tracker);
        }
        return category;
    }
}
