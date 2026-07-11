package net.kollnig.missioncontrol.wg;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reduces noisy Android connectivity callbacks to distinct underlying-network generations.
 * Android scheduling is deliberately kept in ServiceSinkhole so this class is deterministic.
 */
public final class UnderlyingNetworkReducer {
    /** Shared across ServiceSinkhole instances so generations never restart after recreation. */
    private static final AtomicLong NEXT_GENERATION = new AtomicLong(0);
    public static final class Snapshot {
        public final String defaultNetwork;
        public final Set<String> nonVpnNetworks;
        public final long tunEpoch;

        public Snapshot(String defaultNetwork, Set<String> nonVpnNetworks, long tunEpoch) {
            this.defaultNetwork = defaultNetwork;
            this.nonVpnNetworks = Collections.unmodifiableSet(new TreeSet<>(nonVpnNetworks));
            this.tunEpoch = tunEpoch;
        }

        public boolean isConnected() {
            return !nonVpnNetworks.isEmpty();
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Snapshot)) return false;
            Snapshot that = (Snapshot) other;
            return tunEpoch == that.tunEpoch &&
                    java.util.Objects.equals(defaultNetwork, that.defaultNetwork) &&
                    nonVpnNetworks.equals(that.nonVpnNetworks);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(defaultNetwork, nonVpnNetworks, tunEpoch);
        }
    }

    public static final class Event {
        public final long generation;
        public final Snapshot snapshot;
        /** False for a TUN-only invalidation: refresh state, but do not rebind a replacement tunnel. */
        public final boolean rebindRequired;

        private Event(long generation, Snapshot snapshot, boolean rebindRequired) {
            this.generation = generation;
            this.snapshot = snapshot;
            this.rebindRequired = rebindRequired;
        }
    }

    private Snapshot emitted;
    private Snapshot pending;

    public synchronized void offer(Snapshot snapshot) {
        pending = snapshot;
    }

    /** Emits the latest offered snapshot iff it is meaningfully distinct. */
    public synchronized Event flush() {
        Snapshot next = pending;
        pending = null;
        if (next == null || next.equals(emitted)) return null;
        boolean rebindRequired;
        if (emitted == null) {
            rebindRequired = next.isConnected();
        } else {
            boolean connectivityChanged = emitted.isConnected() != next.isConnected();
            boolean defaultChanged =
                    !java.util.Objects.equals(emitted.defaultNetwork, next.defaultNetwork);
            // Mullvad's NOT_VPN stream is primarily a reevaluation trigger.
            // Candidate identity is the effective path only when Android does
            // not provide a usable default-network signature.
            boolean fallbackPathChanged = emitted.defaultNetwork == null &&
                    next.defaultNetwork == null &&
                    !emitted.nonVpnNetworks.equals(next.nonVpnNetworks);
            rebindRequired = connectivityChanged || defaultChanged || fallbackPathChanged;
        }
        emitted = next;
        return new Event(NEXT_GENERATION.incrementAndGet(), next, rebindRequired);
    }
}
