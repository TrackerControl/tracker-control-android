package eu.faircode.netguard;

import static org.junit.Assert.assertFalse;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class RuleSystemClassificationRobolectricTest {

    @Test
    public void chromeUsesPredefinedUserAppClassification() {
        assertFalse(Rule.isSystem("com.android.chrome", RuntimeEnvironment.getApplication()));
    }
}
