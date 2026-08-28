package eu.faircode.netguard;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
public class ActivitySettingsTest {
    @Test
    public void currentImportHasNoObsoleteSettingsWarning() {
        assertFalse(ActivitySettings.hasObsoleteImportSettings(
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyMap()));
    }

    @Test
    public void legacyImportSettingsTriggerWarning() {
        assertTrue(ActivitySettings.hasObsoleteImportSettings(
                Collections.singletonMap("setting", new Object()), Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap()));
    }
}
