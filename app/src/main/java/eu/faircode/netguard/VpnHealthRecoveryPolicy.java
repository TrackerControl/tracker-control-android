package eu.faircode.netguard;

/**
 * Keeps connectivity recovery bounded and independent from the probe transport.
 * A recovery is justified only when the VPN path fails while a protected socket
 * can still reach the Internet.
 */
final class VpnHealthRecoveryPolicy {
    static final int FAILURE_THRESHOLD = 3;
    static final int MAX_RECOVERIES_PER_EPOCH = 2;

    enum Action {
        NONE,
        RETRY,
        RECOVER,
        EXHAUSTED
    }

    private int consecutiveVpnFailures;
    private int recoveries;

    synchronized void beginEpoch() {
        consecutiveVpnFailures = 0;
        recoveries = 0;
    }

    synchronized Action record(boolean vpnReachable, boolean bypassReachable) {
        if (vpnReachable) {
            consecutiveVpnFailures = 0;
            return Action.NONE;
        }

        if (!bypassReachable) {
            consecutiveVpnFailures = 0;
            return Action.NONE;
        }

        consecutiveVpnFailures++;
        if (consecutiveVpnFailures < FAILURE_THRESHOLD)
            return Action.RETRY;

        consecutiveVpnFailures = 0;
        if (recoveries >= MAX_RECOVERIES_PER_EPOCH)
            return Action.EXHAUSTED;

        recoveries++;
        return Action.RECOVER;
    }

    synchronized int getRecoveries() {
        return recoveries;
    }
}
