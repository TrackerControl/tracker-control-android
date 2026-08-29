/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package net.kollnig.missioncontrol.data;

/**
 * Resolves the status and interaction affordance for one tracker company in
 * the per-app tracker feed. This deliberately contains no Android or storage
 * dependencies so that the precedence rules remain directly testable.
 */
public final class TrackerStatusLogic {
    private TrackerStatusLogic() {
    }

    public enum Status {
        BLOCKED,
        ALLOWED,
        ALLOWED_BY_USER,
        ALLOWED_SHARED_IP,
        MONITORED
    }

    public enum Interactivity {
        TOGGLEABLE,
        READ_ONLY,
        NON_INTERACTIVE_SHARED_IP
    }

    public static final class Result {
        public final Status status;
        public final Interactivity interactivity;

        private Result(Status status, Interactivity interactivity) {
            this.status = status;
            this.interactivity = interactivity;
        }

        public Status getStatus() {
            return status;
        }

        public Interactivity getInteractivity() {
            return interactivity;
        }

        public boolean isToggleable() {
            return interactivity == Interactivity.TOGGLEABLE;
        }
    }

    /**
     * Resolve the UI status for a tracker company.
     *
     * @param trackerProtectionEnabled whether tracker protection is enabled
     * @param minimalMode global Minimal blocking mode
     * @param strictMode Strict mode, which blocks ambiguous shared-IP hosts
     * @param state derived per-app protection state
     * @param minimallyBlocked whether a host is blocked by the Minimal list
     * @param minimallyKnown whether a host is known by the Minimal list
     * @param categoryBlocked whether the category whitelist blocks this row
     * @param companyKeyBlocked whether the company whitelist key blocks this row
     * @param allowedInStandardMode whether the observed host has a shared IP
     */
    public static Result resolve(boolean trackerProtectionEnabled,
            boolean minimalMode,
            boolean strictMode,
            AppProtectionState state,
            boolean minimallyBlocked,
            boolean minimallyKnown,
            boolean categoryBlocked,
            boolean companyKeyBlocked,
            boolean allowedInStandardMode) {
        if (!trackerProtectionEnabled)
            return new Result(Status.ALLOWED, Interactivity.READ_ONLY);

        // Minimal mode has its own detection/blocking lists and does not
        // consult the per-app whitelist.
        if (minimalMode) {
            if (minimallyBlocked)
                return new Result(Status.BLOCKED, Interactivity.READ_ONLY);
            if (!minimallyKnown)
                return new Result(Status.MONITORED, Interactivity.READ_ONLY);
            return new Result(Status.ALLOWED, Interactivity.READ_ONLY);
        }

        // A MINIMAL_ONLY app mirrors today's per-app behavior: it reports the
        // Minimal blocking result but has no separate Monitored label.
        if (state == AppProtectionState.MINIMAL_ONLY)
            return new Result(minimallyBlocked ? Status.BLOCKED : Status.ALLOWED,
                    Interactivity.READ_ONLY);

        // Shared-IP ambiguity wins over whitelist state in non-Strict mode.
        // The stored key remains untouched, but changing it would have no
        // runtime effect while the mode allows the shared address.
        if (!strictMode && allowedInStandardMode)
            return new Result(Status.ALLOWED_SHARED_IP,
                    Interactivity.NON_INTERACTIVE_SHARED_IP);

        if (categoryBlocked) {
            if (companyKeyBlocked)
                return new Result(Status.BLOCKED, Interactivity.TOGGLEABLE);
            return new Result(Status.ALLOWED_BY_USER, Interactivity.TOGGLEABLE);
        }

        // A category whitelist is the controlling user choice. Its company
        // switches are therefore read-only and shown checked/allowed.
        return new Result(Status.ALLOWED, Interactivity.READ_ONLY);
    }
}
