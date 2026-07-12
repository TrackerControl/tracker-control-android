package eu.faircode.netguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class VpnReplacementSequencerTest {
    @Test
    public void activatesBlockingTunnelBeforeStoppingPreviousTunnel() {
        List<String> events = new ArrayList<>();

        String result = VpnReplacementSequencer.replace(
                "previous",
                () -> {
                    events.add("establish blocking");
                    return "blocking";
                },
                () -> {
                    events.add("establish replacement");
                    return "replacement";
                },
                value -> events.add("activate " + value),
                value -> events.add("stop " + value),
                value -> events.add("close " + value));

        assertEquals("replacement", result);
        assertEquals(Arrays.asList(
                "establish blocking",
                "activate blocking",
                "stop previous",
                "close previous",
                "establish replacement",
                "activate replacement",
                "close blocking"), events);
    }

    @Test
    public void blockingEstablishmentFailureLeavesPreviousTunnelUntouched() {
        List<String> events = new ArrayList<>();

        assertThrows(VpnReplacementSequencer.EstablishFailedException.class,
                () -> VpnReplacementSequencer.replace(
                        "previous",
                        () -> null,
                        () -> "replacement",
                        value -> events.add("activate " + value),
                        value -> events.add("stop " + value),
                        value -> events.add("close " + value)));

        assertEquals(0, events.size());
    }

    @Test
    public void replacementFailureKeepsBlockingTunnelActive() {
        List<String> active = new ArrayList<>();
        List<String> closed = new ArrayList<>();

        assertThrows(VpnReplacementSequencer.EstablishFailedException.class,
                () -> VpnReplacementSequencer.replace(
                        "previous",
                        () -> "blocking",
                        () -> null,
                        active::add,
                        value -> { },
                        closed::add));

        assertEquals(1, active.size());
        assertEquals("blocking", active.get(0));
        assertEquals(Arrays.asList("previous"), closed);
    }

    @Test
    public void previousStopFailureLeavesBlockingTunnelActive() {
        List<String> active = new ArrayList<>();
        List<String> closed = new ArrayList<>();

        assertThrows(IllegalStateException.class,
                () -> VpnReplacementSequencer.replace(
                        "previous",
                        () -> "blocking",
                        () -> "replacement",
                        active::add,
                        value -> {
                            throw new IllegalStateException("stop failed");
                        },
                        closed::add));

        assertEquals(Arrays.asList("blocking"), active);
        assertEquals(0, closed.size());
    }
}
