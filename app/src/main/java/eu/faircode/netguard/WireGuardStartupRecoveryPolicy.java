/*
 * This file is part of TrackerControl.
 *
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation.
 */

package eu.faircode.netguard;

/**
 * Bounded retry state for a WireGuard startup that left the VPN descriptor
 * active but could not start the packet engine.
 *
 * <p>This policy deliberately has its own budget: a failed startup is not a
 * native-thread failure or a failed VPN replacement, and those recovery
 * episodes must not consume one another's retries.</p>
 */
final class WireGuardStartupRecoveryPolicy {
    static final long NO_RETRY = FailureRecoveryPolicy.NO_RETRY;

    private final FailureRecoveryPolicy retryPolicy;
    private boolean pending;
    private boolean dispatched;

    WireGuardStartupRecoveryPolicy(int maxRetries, long initialDelayMs, long stableWindowMs) {
        retryPolicy = new FailureRecoveryPolicy(maxRetries, initialDelayMs, stableWindowMs);
    }

    /**
     * Records a failure and claims a single pending retry slot. A disabled
     * service never schedules work, even if a stale failure callback arrives.
     */
    synchronized long onFailure(long nowMs, boolean active) {
        if (!active) {
            pending = false;
            dispatched = false;
            return NO_RETRY;
        }
        if (pending || dispatched)
            return NO_RETRY;

        long delayMs = retryPolicy.onFailure(nowMs);
        if (delayMs != NO_RETRY)
            pending = true;
        return delayMs;
    }

    /** Claims the queued retry so a callback can dispatch at most once. */
    synchronized boolean claim() {
        if (!pending)
            return false;
        pending = false;
        dispatched = true;
        return true;
    }

    /** Marks the tagged command as running and consumes its dispatch marker. */
    synchronized boolean begin() {
        if (!dispatched)
            return false;
        dispatched = false;
        return true;
    }

    /** Successful startup clears a pending callback and restores the budget. */
    synchronized void onSuccess() {
        pending = false;
        dispatched = false;
        retryPolicy.reset();
    }

    /** Cancels queued work; callers choose whether this is a new episode. */
    synchronized void cancel(boolean resetBudget) {
        pending = false;
        dispatched = false;
        if (resetBudget)
            retryPolicy.reset();
    }

    synchronized boolean isPending() {
        return pending || dispatched;
    }
}
