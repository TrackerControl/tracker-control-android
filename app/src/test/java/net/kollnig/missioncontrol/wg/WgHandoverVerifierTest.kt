package net.kollnig.missioncontrol.wg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WgHandoverVerifierTest {
    private val target = WgProbeTarget("10.0.0.2", "1.1.1.1")

    private fun stats(delivered: Long = 0L, token: Long = 0L) = WgStats(
        rxBytes = 0L,
        txBytes = 0L,
        latestHandshakeMillis = 0L,
        deliveredRxBytes = delivered,
        probeReplyToken = token
    )

    @Test
    fun startsWithProbeThenRetriesAtFiveSecondCadenceAndRestartsAtDeadline() {
        val verifier = WgHandoverVerifier()
        val first = verifier.begin(7, stats(10), listOf(target), true, now = 0L)
        assertTrue(first is WgHandoverVerifier.Action.Probe)
        assertTrue(verifier.onSample(7, stats(10), true, now = 4_999L) is WgHandoverVerifier.Action.None)
        assertTrue(verifier.onSample(7, stats(10), true, now = 5_000L) is WgHandoverVerifier.Action.Probe)
        assertTrue(verifier.onSample(7, stats(10), true, now = 10_000L) is WgHandoverVerifier.Action.Probe)
        assertTrue(verifier.onSample(7, stats(10), true, now = 14_999L) is WgHandoverVerifier.Action.None)
        assertTrue(verifier.onSample(7, stats(10), true, now = 15_000L) is WgHandoverVerifier.Action.Restart)
        assertFalse(verifier.isPending())
    }

    @Test
    fun deliveredTrafficConfirmsHandoverWithoutProbeReply() {
        val verifier = WgHandoverVerifier()
        verifier.begin(1, stats(10), listOf(target), true, now = 0L)
        assertTrue(verifier.onSample(1, stats(11), true, now = 1_000L) is WgHandoverVerifier.Action.None)
        assertFalse(verifier.isPending())
    }

    @Test
    fun matchingProbeTokenConfirmsHandover() {
        val verifier = WgHandoverVerifier()
        val action = verifier.begin(2, stats(), listOf(target), true, now = 0L)
                as WgHandoverVerifier.Action.Probe
        assertTrue(verifier.onSample(2, stats(token = action.token), true, now = 1_000L)
            is WgHandoverVerifier.Action.None)
        assertFalse(verifier.isPending())
    }

    @Test
    fun screenOffDefersAndRebasesVerification() {
        val verifier = WgHandoverVerifier()
        assertTrue(verifier.begin(3, stats(10), listOf(target), false, now = 0L)
            is WgHandoverVerifier.Action.None)
        assertTrue(verifier.onSample(3, stats(20), false, now = 20_000L)
            is WgHandoverVerifier.Action.None)
        assertTrue(verifier.onSample(3, stats(20), true, now = 21_000L)
            is WgHandoverVerifier.Action.Probe)
        assertTrue(verifier.onSample(3, stats(20), true, now = 26_000L)
            is WgHandoverVerifier.Action.Probe)
        assertTrue(verifier.onSample(3, stats(20), true, now = 31_000L)
            is WgHandoverVerifier.Action.Probe)
        assertTrue(verifier.onSample(3, stats(20), true, now = 35_999L)
            is WgHandoverVerifier.Action.None)
        assertTrue(verifier.onSample(3, stats(20), true, now = 36_000L)
            is WgHandoverVerifier.Action.Restart)
    }

    @Test
    fun interactiveTransitionRebasesDeadline() {
        val verifier = WgHandoverVerifier()
        verifier.begin(8, stats(), listOf(target), true, now = 0L)
        assertTrue(verifier.onSample(8, stats(), true, now = 1_000L)
            is WgHandoverVerifier.Action.None)

        verifier.setInteractive(false)
        // No monitor poll occurs while off: the explicit event must retain
        // the suspension even after the screen comes back on.
        verifier.setInteractive(true)
        assertTrue(verifier.onSample(8, stats(), true, now = 11_000L)
            is WgHandoverVerifier.Action.Probe)
        assertTrue(verifier.onSample(8, stats(), true, now = 16_000L)
            is WgHandoverVerifier.Action.Probe)
        assertTrue(verifier.onSample(8, stats(), true, now = 21_000L)
            is WgHandoverVerifier.Action.Probe)
        assertTrue(verifier.onSample(8, stats(), true, now = 25_999L)
            is WgHandoverVerifier.Action.None)
        assertTrue(verifier.onSample(8, stats(), true, now = 26_000L)
            is WgHandoverVerifier.Action.Restart)
    }

    @Test
    fun suspendedStateRebasesDeadlineOnResume() {
        val verifier = WgHandoverVerifier()
        verifier.begin(9, stats(), listOf(target), true, now = 0L)
        assertTrue(verifier.onSample(9, stats(), true, now = 0L)
            is WgHandoverVerifier.Action.None)

        verifier.onSuspended()
        assertTrue(verifier.onSample(9, stats(), true, now = 100_000L)
            is WgHandoverVerifier.Action.Probe)
        assertTrue(verifier.onSample(9, stats(), true, now = 105_000L)
            is WgHandoverVerifier.Action.Probe)
        assertTrue(verifier.onSample(9, stats(), true, now = 110_000L)
            is WgHandoverVerifier.Action.Probe)
        assertTrue(verifier.onSample(9, stats(), true, now = 114_999L)
            is WgHandoverVerifier.Action.None)
        assertTrue(verifier.onSample(9, stats(), true, now = 115_000L)
            is WgHandoverVerifier.Action.Restart)
    }

    @Test
    fun newBeginInvalidatesQueuedReplyFromPreviousGeneration() {
        val verifier = WgHandoverVerifier()
        val oldProbe = verifier.begin(10, stats(), listOf(target), true, now = 0L)
            as WgHandoverVerifier.Action.Probe
        val newProbe = verifier.begin(11, stats(), listOf(target), true, now = 100L)
            as WgHandoverVerifier.Action.Probe

        assertFalse(oldProbe.token == newProbe.token)
        assertTrue(verifier.onSample(11, stats(token = oldProbe.token), true, now = 101L)
            is WgHandoverVerifier.Action.None)
        assertTrue(verifier.isPending())
        assertTrue(verifier.onSample(11, stats(token = newProbe.token), true, now = 102L)
            is WgHandoverVerifier.Action.None)
        assertFalse(verifier.isPending())
    }

    @Test
    fun staleGenerationCannotConfirmOrRestart() {
        val verifier = WgHandoverVerifier()
        verifier.begin(4, stats(), listOf(target), true, now = 0L)
        assertTrue(verifier.onSample(5, stats(100), true, now = 20_000L)
            is WgHandoverVerifier.Action.None)
        assertTrue(verifier.isPending())
    }

    @Test
    fun unsupportedTargetsLeavePassiveWatchdogAlone() {
        val verifier = WgHandoverVerifier()
        assertTrue(verifier.begin(6, stats(), emptyList(), true, now = 0L)
            is WgHandoverVerifier.Action.None)
        assertFalse(verifier.isPending())
    }

    @Test
    fun selectorRejectsMixedFamilyAndUnroutedResolvers() {
        val targets = WgProbeTargetSelector.select(
            sourceAddresses = listOf("10.0.0.2/32", "2001:db8::2/128"),
            resolverAddresses = listOf("1.1.1.1", "2001:4860:4860::8888", "resolver.example"),
            allowedIps = listOf("0.0.0.0/0", "2001::/16")
        )
        assertEquals(
            listOf(
                WgProbeTarget("10.0.0.2", "1.1.1.1"),
                WgProbeTarget("2001:db8::2", "2001:4860:4860::8888")
            ),
            targets
        )
    }

    @Test
    fun selectorRejectsResolverOutsideAllowedIps() {
        assertTrue(
            WgProbeTargetSelector.select(
                listOf("10.0.0.2/32"),
                listOf("1.1.1.1"),
                listOf("10.0.0.0/8")
            ).isEmpty()
        )
    }

    @Test
    fun selectorRejectsSpecialDestinationsAndMalformedRoutes() {
        assertTrue(
            WgProbeTargetSelector.select(
                listOf("0.0.0.0/32"),
                listOf("224.0.0.1"),
                listOf("0.0.0.0/0")
            ).isEmpty()
        )
        assertTrue(
            WgProbeTargetSelector.select(
                listOf("127.0.0.1/32"),
                listOf("1.1.1.1"),
                listOf("0.0.0.0/not-a-prefix")
            ).isEmpty()
        )
    }
}
