package eu.faircode.netguard;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TrackerEvidenceExpiryTest {
    @Test
    public void mixedVerdictExpiresWhenBenignEvidenceExpiresFirst() {
        long selectedTrackerExpiry = TrackerEvidenceExpiry.at(1_000L, 5_000L);
        long benignExpiry = TrackerEvidenceExpiry.at(900L, 1_000L);

        assertEquals(1_900L,
                TrackerEvidenceExpiry.mixed(selectedTrackerExpiry, benignExpiry));
    }

    @Test
    public void mixedVerdictUsesLastFreshBenignRow() {
        long selectedTrackerExpiry = TrackerEvidenceExpiry.at(1_000L, 5_000L);
        long firstBenignExpiry = TrackerEvidenceExpiry.at(900L, 500L);
        long lastBenignExpiry = TrackerEvidenceExpiry.at(950L, 2_000L);

        assertEquals(2_950L,
                TrackerEvidenceExpiry.mixed(selectedTrackerExpiry,
                        Math.max(firstBenignExpiry, lastBenignExpiry)));
    }

    @Test
    public void minimalMixedVerdictUsesTheSameEvidenceRule() {
        long selectedMinimalExpiry = TrackerEvidenceExpiry.at(4_000L, 2_000L);
        long lastNonMinimalExpiry = TrackerEvidenceExpiry.at(3_500L, 100L);

        assertEquals(3_600L,
                TrackerEvidenceExpiry.mixed(selectedMinimalExpiry, lastNonMinimalExpiry));
    }

    @Test
    public void expiryArithmeticSaturatesAtLongBounds() {
        assertEquals(Long.MAX_VALUE,
                TrackerEvidenceExpiry.at(Long.MAX_VALUE - 1L, 2L));
        assertEquals(Long.MIN_VALUE,
                TrackerEvidenceExpiry.at(Long.MIN_VALUE + 1L, -2L));
    }
}
