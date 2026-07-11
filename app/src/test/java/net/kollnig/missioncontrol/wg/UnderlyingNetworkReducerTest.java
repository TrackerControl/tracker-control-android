package net.kollnig.missioncontrol.wg;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import org.junit.Test;

public class UnderlyingNetworkReducerTest {
    private static UnderlyingNetworkReducer.Snapshot snapshot(
            String defaultNetwork, long tunEpoch, String... networks) {
        return new UnderlyingNetworkReducer.Snapshot(
                defaultNetwork, new HashSet<>(Arrays.asList(networks)), tunEpoch);
    }

    @Test public void eagerSnapshotGetsPositiveGeneration() {
        UnderlyingNetworkReducer reducer = new UnderlyingNetworkReducer();
        reducer.offer(snapshot("wifi", 0, "wifi"));
        UnderlyingNetworkReducer.Event event = reducer.flush();
        assertNotNull(event);
        assertTrue(event.generation > 0);
        assertTrue(event.snapshot.isConnected());
        assertTrue(event.rebindRequired);
    }

    @Test public void burstCollapsesToLatestSnapshot() {
        UnderlyingNetworkReducer reducer = new UnderlyingNetworkReducer();
        reducer.offer(snapshot("wifi", 0, "wifi"));
        reducer.offer(snapshot("cell", 0, "wifi", "cell"));
        reducer.offer(snapshot("cell", 0, "cell"));
        UnderlyingNetworkReducer.Event event = reducer.flush();
        assertTrue(event.generation > 0);
        assertEquals("cell", event.snapshot.defaultNetwork);
        assertEquals(Collections.singleton("cell"), event.snapshot.nonVpnNetworks);
    }

    @Test public void redundantCallbacksDoNotAdvanceGeneration() {
        UnderlyingNetworkReducer reducer = new UnderlyingNetworkReducer();
        reducer.offer(snapshot("wifi", 0, "wifi"));
        long firstGeneration = reducer.flush().generation;
        reducer.offer(snapshot("wifi", 0, "wifi"));
        assertNull(reducer.flush());
        reducer.offer(snapshot("cell", 0, "cell"));
        assertTrue(reducer.flush().generation > firstGeneration);
    }

    @Test public void sameNetworkWithChangedPathSignatureAdvancesGeneration() {
        UnderlyingNetworkReducer reducer = new UnderlyingNetworkReducer();
        reducer.offer(snapshot("wifi|dns=one", 0, "wifi|dns=one"));
        long firstGeneration = reducer.flush().generation;
        reducer.offer(snapshot("wifi|dns=two", 0, "wifi|dns=two"));
        UnderlyingNetworkReducer.Event event = reducer.flush();
        assertTrue(event.generation > firstGeneration);
        assertTrue(event.rebindRequired);
    }

    @Test public void candidatePresenceDefinesConnectivityEvenWithoutDefault() {
        UnderlyingNetworkReducer reducer = new UnderlyingNetworkReducer();
        reducer.offer(snapshot(null, 0, "cell"));
        assertTrue(reducer.flush().snapshot.isConnected());
        reducer.offer(snapshot(null, 0));
        assertFalse(reducer.flush().snapshot.isConnected());
    }

    @Test public void secondaryCandidateDoesNotRebindWhenDefaultIsUnchanged() {
        UnderlyingNetworkReducer reducer = new UnderlyingNetworkReducer();
        reducer.offer(snapshot("wifi", 0, "wifi"));
        reducer.flush();
        reducer.offer(snapshot("wifi", 0, "wifi", "cell"));
        UnderlyingNetworkReducer.Event event = reducer.flush();
        assertNotNull(event);
        assertTrue(event.generation > 0);
        assertFalse(event.rebindRequired);
    }

    @Test public void candidateHandoffRebindsWhenDefaultIsUnavailable() {
        UnderlyingNetworkReducer reducer = new UnderlyingNetworkReducer();
        reducer.offer(snapshot(null, 0, "wifi"));
        reducer.flush();
        reducer.offer(snapshot(null, 0, "cell"));
        UnderlyingNetworkReducer.Event event = reducer.flush();
        assertNotNull(event);
        assertTrue(event.generation > 0);
        assertTrue(event.rebindRequired);
    }

    @Test public void tunTransitionForcesFreshGeneration() {
        UnderlyingNetworkReducer reducer = new UnderlyingNetworkReducer();
        reducer.offer(snapshot("wifi", 0, "wifi"));
        long firstGeneration = reducer.flush().generation;
        reducer.offer(snapshot("wifi", 1, "wifi"));
        UnderlyingNetworkReducer.Event event = reducer.flush();
        assertTrue(event.generation > firstGeneration);
        assertFalse(event.rebindRequired);
    }

    @Test public void generationsIncreaseAcrossReducerInstances() {
        UnderlyingNetworkReducer first = new UnderlyingNetworkReducer();
        first.offer(snapshot("wifi", 0, "wifi"));
        long firstGeneration = first.flush().generation;

        UnderlyingNetworkReducer replacement = new UnderlyingNetworkReducer();
        replacement.offer(snapshot("cell", 0, "cell"));
        long replacementGeneration = replacement.flush().generation;

        assertTrue(replacementGeneration > firstGeneration);
    }
}
