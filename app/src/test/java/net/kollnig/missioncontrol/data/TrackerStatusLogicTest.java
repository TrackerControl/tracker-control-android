package net.kollnig.missioncontrol.data;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TrackerStatusLogicTest {
    private static final AppProtectionState PROTECTED = AppProtectionState.PROTECTED;

    @Test
    public void protectionOffIsReadOnlyAllowed() {
        TrackerStatusLogic.Result result = resolve(false, false, false, false, false, true, true);
        assertEquals(TrackerStatusLogic.Status.ALLOWED, result.status);
        assertEquals(TrackerStatusLogic.Interactivity.READ_ONLY, result.interactivity);
    }

    @Test
    public void appRoutingStatePrecedesDormantTrackerPolicy() {
        TrackerStatusLogic.Result bypassed = TrackerStatusLogic.resolve(
                true, true, true, AppProtectionState.BYPASSED,
                true, true, true, true, false);
        assertEquals(TrackerStatusLogic.Status.ALLOWED, bypassed.status);
        assertEquals(TrackerStatusLogic.Interactivity.READ_ONLY, bypassed.interactivity);

        TrackerStatusLogic.Result noInternet = TrackerStatusLogic.resolve(
                false, false, false, AppProtectionState.NO_INTERNET,
                false, false, false, false, true);
        assertEquals(TrackerStatusLogic.Status.BLOCKED, noInternet.status);
        assertEquals(TrackerStatusLogic.Interactivity.READ_ONLY, noInternet.interactivity);
    }

    @Test
    public void minimalModeDistinguishesBlockedMonitoredAndAllowed() {
        TrackerStatusLogic.Result blocked = resolve(
                true, true, false, true, true, true, false);
        assertEquals(TrackerStatusLogic.Status.BLOCKED, blocked.status);
        assertEquals(TrackerStatusLogic.Interactivity.READ_ONLY, blocked.interactivity);
        TrackerStatusLogic.Result monitored = resolve(
                true, true, false, false, false, false, false);
        assertEquals(TrackerStatusLogic.Status.MONITORED, monitored.status);
        assertEquals(TrackerStatusLogic.Interactivity.READ_ONLY, monitored.interactivity);
        assertEquals(TrackerStatusLogic.Status.ALLOWED,
                TrackerStatusLogic.resolve(true, true, false, PROTECTED,
                        false, true, false, true, false).status);
    }

    @Test
    public void minimalOnlyHasNoMonitoredStatus() {
        TrackerStatusLogic.Result blocked = TrackerStatusLogic.resolve(
                true, false, false, AppProtectionState.MINIMAL_ONLY,
                true, false, true, true, false);
        assertEquals(TrackerStatusLogic.Status.BLOCKED, blocked.status);
        assertEquals(TrackerStatusLogic.Interactivity.READ_ONLY, blocked.interactivity);
        assertEquals(TrackerStatusLogic.Status.ALLOWED,
                TrackerStatusLogic.resolve(true, false, false, AppProtectionState.MINIMAL_ONLY,
                        false, false, true, true, false).status);
    }

    @Test
    public void standardResolvesWhitelistAndCategoryStates() {
        TrackerStatusLogic.Result companyAllowed = resolve(
                true, false, false, true, false, true, false);
        assertEquals(TrackerStatusLogic.Status.ALLOWED_BY_USER, companyAllowed.status);
        assertEquals(TrackerStatusLogic.Interactivity.TOGGLEABLE, companyAllowed.interactivity);
        TrackerStatusLogic.Result categoryAllowed = resolve(true, false, false,
                false, false, true, false);
        assertEquals(TrackerStatusLogic.Status.ALLOWED, categoryAllowed.status);
        assertEquals(TrackerStatusLogic.Interactivity.READ_ONLY, categoryAllowed.interactivity);
    }

    @Test
    public void sharedIpPrecedesWhitelistAndStrictOverridesIt() {
        assertEquals(TrackerStatusLogic.Status.ALLOWED_SHARED_IP,
                resolve(true, false, false, true, true, true, true).status);
        assertEquals(TrackerStatusLogic.Status.ALLOWED_SHARED_IP,
                resolve(true, false, false, false, false, true, true).status);
        TrackerStatusLogic.Result companyAllowed = resolve(
                true, false, false, true, false, true, true);
        assertEquals(TrackerStatusLogic.Status.ALLOWED_SHARED_IP, companyAllowed.status);
        assertEquals(TrackerStatusLogic.Interactivity.NON_INTERACTIVE_SHARED_IP,
                companyAllowed.interactivity);
        assertEquals(TrackerStatusLogic.Status.BLOCKED,
                resolve(true, false, true, true, true, true, true).status);
    }

    @Test
    public void noSubsetStateIsBlocked() {
        assertEquals(TrackerStatusLogic.Status.BLOCKED,
                resolve(true, false, false, true, true, true, false).status);
    }

    private static TrackerStatusLogic.Result resolve(boolean protection, boolean minimal,
            boolean strict, boolean categoryBlocked, boolean companyBlocked,
            boolean minimallyBlocked, boolean allowedSharedIp) {
        return TrackerStatusLogic.resolve(protection, minimal, strict, PROTECTED,
                minimallyBlocked, minimallyBlocked, categoryBlocked, companyBlocked,
                allowedSharedIp);
    }
}
