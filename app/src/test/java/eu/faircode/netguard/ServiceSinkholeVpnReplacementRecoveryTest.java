/*
 * This file is part of TrackerControl.
 *
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package eu.faircode.netguard;

import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class ServiceSinkholeVpnReplacementRecoveryTest {
    @Test
    public void exhaustionNotificationExplainsThatTrafficIsBlocked() {
        Context context = RuntimeEnvironment.getApplication();
        VpnReplacementRecoveryPolicy policy = new VpnReplacementRecoveryPolicy(1, 1_000L);
        assertTrue(policy.onFailure() >= 0L);
        assertTrue(policy.onFailure() == VpnReplacementRecoveryPolicy.NO_RETRY);

        String message = ServiceSinkhole.vpnReplacementRecoveryExhaustedMessage(context);

        assertTrue(message.contains("re-establish"));
        assertTrue(message.contains("blocked"));
    }
}
