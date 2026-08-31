package net.kollnig.missioncontrol.ui.compose

import android.content.Context
import android.view.ContextThemeWrapper
import androidx.compose.ui.graphics.toArgb
import net.kollnig.missioncontrol.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class TrackerControlThemeTest {
    @Test
    @Config(qualifiers = "notnight")
    fun lightColourSchemeMatchesMaterialThemeAttributes() {
        val context = themedContext()

        assertSchemeMatchesTheme(context, trackerControlLightColours(context))
    }

    @Test
    @Config(qualifiers = "night")
    fun darkColourSchemeMatchesMaterialThemeAttributes() {
        val context = themedContext()

        assertSchemeMatchesTheme(context, trackerControlDarkColours(context))
    }

    private fun themedContext(): Context {
        val application = RuntimeEnvironment.getApplication()
        return ContextThemeWrapper(application, R.style.AppThemeRed)
    }

    private fun assertSchemeMatchesTheme(
        context: Context,
        scheme: androidx.compose.material3.ColorScheme
    ) {
        assertEquals(themeColour(context, android.R.attr.colorBackground), scheme.background.toArgb())
        assertEquals(
            themeColour(context, com.google.android.material.R.attr.colorOnBackground),
            scheme.onBackground.toArgb()
        )
        assertEquals(
            themeColour(context, com.google.android.material.R.attr.colorSurface),
            scheme.surface.toArgb()
        )
        assertEquals(
            themeColour(context, com.google.android.material.R.attr.colorOnSurface),
            scheme.onSurface.toArgb()
        )
        assertEquals(
            themeColour(context, com.google.android.material.R.attr.colorSurfaceContainer),
            scheme.surfaceVariant.toArgb()
        )
        assertEquals(
            themeColour(context, com.google.android.material.R.attr.colorOnSurfaceVariant),
            scheme.onSurfaceVariant.toArgb()
        )
        assertEquals(
            themeColour(context, com.google.android.material.R.attr.colorOutline),
            scheme.outline.toArgb()
        )
        assertEquals(
            themeColour(context, com.google.android.material.R.attr.colorOutlineVariant),
            scheme.outlineVariant.toArgb()
        )
    }

    private fun themeColour(context: Context, attribute: Int): Int {
        val value = android.util.TypedValue()
        assertTrue("Theme attribute $attribute must be resource-backed", context.theme.resolveAttribute(attribute, value, true))
        assertTrue("Theme attribute $attribute must resolve to a colour resource", value.resourceId != 0)
        return androidx.core.content.ContextCompat.getColor(context, value.resourceId)
    }
}
