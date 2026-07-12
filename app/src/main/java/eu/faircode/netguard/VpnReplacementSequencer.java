package eu.faircode.netguard;

final class VpnReplacementSequencer {
    interface Establish<T> {
        T establish();
    }

    interface Action<T> {
        void run(T value);
    }

    static final class EstablishFailedException extends IllegalStateException {
        EstablishFailedException(String stage) {
            super("Could not establish " + stage + " VPN");
        }
    }

    private VpnReplacementSequencer() {
    }

    static <T> T replace(
            T previous,
            Establish<T> establishBlocking,
            Establish<T> establishReplacement,
            Action<T> setActive,
            Action<T> stopPrevious,
            Action<T> close) {
        T blocking = establishBlocking.establish();
        if (blocking == null)
            throw new EstablishFailedException("blocking");

        // Publish the blocking tunnel before touching the previous one so a
        // failure always leaves the owner with a live, fail-closed descriptor.
        setActive.run(blocking);

        if (previous != null) {
            stopPrevious.run(previous);
            close.run(previous);
        }

        T replacement = establishReplacement.establish();
        if (replacement == null)
            throw new EstablishFailedException("replacement");

        // The establish call is the readiness boundary: Android has created
        // and activated the new interface before returning its descriptor.
        setActive.run(replacement);
        close.run(blocking);
        return replacement;
    }
}
