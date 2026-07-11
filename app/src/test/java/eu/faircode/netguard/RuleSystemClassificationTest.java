package eu.faircode.netguard;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RuleSystemClassificationTest {

    @Test
    public void chromeUsesPredefinedUserAppClassification() {
        assertFalse(Rule.resolveSystemClassification(true, false));
    }

    @Test
    public void rawClassificationIsUsedWithoutOverride() {
        assertTrue(Rule.resolveSystemClassification(true, null));
        assertFalse(Rule.resolveSystemClassification(false, null));
    }
}
