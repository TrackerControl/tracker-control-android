package eu.faircode.netguard;

import java.util.ArrayDeque;

/**
 * Keeps connectivity recovery bounded and independent from the probe transport.
 * A recovery is justified only when the VPN path fails while a protected socket
 * can still reach the Internet.
 */
final class VpnHealthRecoveryPolicy {
    static final int FAILURE_THRESHOLD = 3;
    static final int MAX_RECOVERIES_PER_EPOCH = 2;

    // Absolute, epoch-independent safety net. The per-epoch budget above is
    // reset by beginEpoch(), which normally only happens on startup or a real
    // network change. If recovery's own reload() were ever to indirectly
    // re-trigger the network callback that calls beginEpoch() (unverified,
    // but not provably impossible), the per-epoch budget alone could be
    // repeatedly re-armed, producing a reload storm. This rolling wall-clock
    // cap bounds total recoveries regardless of how many epochs occur, acting
    // as an AND-gate on top of the existing epoch budget. Values are chosen
    // conservatively relative to the existing recovery cadence (retries every
    // VPN_HEALTH_RETRY_DELAY_MS=5s, a recovery re-check after
    // VPN_HEALTH_STARTUP_DELAY_MS=5s): 4 recoveries per 15 minutes still
    // allows a couple of legitimate epoch resets (e.g. two real network
    // changes) to each recover a couple of times, while making a runaway
    // storm impossible.
    static final int MAX_RECOVERIES_PER_WINDOW = 4;
    static final long RECOVERY_WINDOW_MS = 15 * 60 * 1000L;

    interface Clock {
        long nowMs();
    }

    enum Action {
        NONE,
        RETRY,
        RECOVER,
        EXHAUSTED
    }

    private final Clock clock;
    private final ArrayDeque<Long> recoveryTimestamps = new ArrayDeque<>();

    private int consecutiveVpnFailures;
    private int recoveries;

    VpnHealthRecoveryPolicy() {
        this(System::currentTimeMillis);
    }

    VpnHealthRecoveryPolicy(Clock clock) {
        this.clock = clock;
    }

    synchronized void beginEpoch() {
        consecutiveVpnFailures = 0;
        recoveries = 0;
        // recoveryTimestamps is intentionally NOT cleared here: the whole
        // point of the wall-clock cap is that it survives epoch resets.
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

        long now = clock.nowMs();
        pruneRecoveryTimestamps(now);
        if (recoveryTimestamps.size() >= MAX_RECOVERIES_PER_WINDOW)
            return Action.EXHAUSTED;

        recoveries++;
        recoveryTimestamps.addLast(now);
        return Action.RECOVER;
    }

    private void pruneRecoveryTimestamps(long now) {
        while (!recoveryTimestamps.isEmpty() && now - recoveryTimestamps.peekFirst() > RECOVERY_WINDOW_MS)
            recoveryTimestamps.pollFirst();
    }

    synchronized int getRecoveries() {
        return recoveries;
    }
}
