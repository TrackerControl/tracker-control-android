package net.kollnig.missioncontrol.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TrackerAnalysisManagerTest {
    @Test
    public void countTrackersCountsBulletPrefixedAnalysisRows() {
        assertEquals(0, TrackerAnalysisManager.countTrackers(null));
        assertEquals(0, TrackerAnalysisManager.countTrackers("None"));
        assertEquals(1, TrackerAnalysisManager.countTrackers("\n• Google"));
        assertEquals(3, TrackerAnalysisManager.countTrackers("\n• Google\n• Meta\n• Branch"));
    }

    @Test
    public void workNameIsUniquePerPackage() {
        assertEquals("tracker_analysis_org.example.one",
                TrackerAnalysisManager.getWorkName("org.example.one"));
        assertEquals("tracker_analysis_org.example.two",
                TrackerAnalysisManager.getWorkName("org.example.two"));
    }

    @Test
    public void analysisStartsWhenCacheIsMissingOrStale() {
        assertTrue(TrackerAnalysisManager.shouldStartAnalysis(null, false, false));
        assertTrue(TrackerAnalysisManager.shouldStartAnalysis("\n• Google", true, false));
        assertFalse(TrackerAnalysisManager.shouldStartAnalysis("\n• Google", false, false));
    }

    @Test
    public void analysisDoesNotAutoRepeatForAlreadyAttemptedVersion() {
        assertFalse(TrackerAnalysisManager.shouldStartAnalysis(null, false, true));
        assertFalse(TrackerAnalysisManager.shouldStartAnalysis("\n• Google", true, true));
    }
}
