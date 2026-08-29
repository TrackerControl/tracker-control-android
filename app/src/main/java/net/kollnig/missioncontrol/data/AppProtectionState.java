/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * TrackerControl is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * Copyright © 2026
 */

package net.kollnig.missioncontrol.data;

/**
 * The single per-app protection state shown in the app details header.
 * <p>
 * It is a derived view over four stores — the per-package "apply",
 * "tracker_protect", and "tracker_essential" preferences and the per-UID
 * internet blocklist — none of which change format here. Those stores are written by
 * more actors than the two UI paths (bulk settings actions, the beta
 * vpn_exclude migration, blocking-mode exclusion sync, XML import), so every
 * one of the sixteen combinations can legitimately exist on disk.
 * {@link #resolve} is therefore a total derivation, not an invariant.
 */
public enum AppProtectionState {
    /** Routed through the VPN, trackers blocked and recorded. */
    PROTECTED,
    /** Routed through the VPN, with only minimal trackers blocked and recorded. */
    MINIMAL_ONLY,
    /** Routed through the VPN, but trackers are neither blocked nor recorded. */
    TRACKERS_ALLOWED,
    /** Routed through the VPN, but all connections are dropped. */
    NO_INTERNET,
    /** Outside the VPN entirely; TrackerControl sees nothing of this app. */
    BYPASSED;

    /**
     * Derive the state from the four stores.
     * <p>
     * Precedence is Bypass, then No-internet, then the protection flag. Bypass
     * dominates for a mechanical reason rather than as a UI convention: an app
     * with {@code apply == false} is handed to
     * {@code Builder#addDisallowedApplication}, so its packets never reach the
     * tun and the per-UID internet block cannot be enforced for it.
     *
     * @param apply           the per-package "apply" preference
     * @param trackerProtect  the resolved tracker protection flag, i.e. the
     *                        per-package preference with the browser default
     *                        already applied (see
     *                        {@link BlockingMode#isTrackerProtectionEnabled})
     * @param internetBlocked whether the app's UID is in the internet blocklist
     * @param minimalOnly     whether only minimal trackers should be blocked
     */
    public static AppProtectionState resolve(boolean apply,
            boolean trackerProtect,
            boolean internetBlocked) {
        return resolve(apply, trackerProtect, internetBlocked, false);
    }

    public static AppProtectionState resolve(boolean apply,
            boolean trackerProtect,
            boolean internetBlocked,
            boolean minimalOnly) {
        if (!apply)
            return BYPASSED;
        if (internetBlocked)
            return NO_INTERNET;
        if (!trackerProtect)
            return TRACKERS_ALLOWED;
        return minimalOnly ? MINIMAL_ONLY : PROTECTED;
    }

    /**
     * The writes that move an app into {@code target}.
     * <p>
     * States that do not depend on a store leave it alone, so switching to
     * "No internet" and back does not silently discard the app's tracker
     * protection choice.
     */
    public static Change of(AppProtectionState target) {
        switch (target) {
            case PROTECTED:
                return new Change(true, Boolean.TRUE, Boolean.FALSE, Boolean.FALSE);
            case MINIMAL_ONLY:
                return new Change(true, Boolean.TRUE, Boolean.FALSE, Boolean.TRUE);
            case TRACKERS_ALLOWED:
                return new Change(true, Boolean.FALSE, Boolean.FALSE, null);
            case NO_INTERNET:
                return new Change(true, null, Boolean.TRUE, null);
            case BYPASSED:
                return new Change(false, null, null, null);
            default:
                throw new IllegalArgumentException("Unknown state " + target);
        }
    }

    /**
     * A write plan over the four stores. A {@code null} field means the store is
     * left untouched.
     */
    public static final class Change {
        public final boolean apply;
        public final Boolean trackerProtect;
        public final Boolean internetBlocked;
        public final Boolean minimalOnly;

        Change(boolean apply, Boolean trackerProtect, Boolean internetBlocked,
                Boolean minimalOnly) {
            this.apply = apply;
            this.trackerProtect = trackerProtect;
            this.internetBlocked = internetBlocked;
            this.minimalOnly = minimalOnly;
        }
    }
}
