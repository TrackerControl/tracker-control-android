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
 * You should have received a copy of the GNU General Public License
 * along with TrackerControl. If not, see <http://www.gnu.org/licenses/>.
 */
package net.kollnig.missioncontrol.ui.compose

import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.ColorRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import net.kollnig.missioncontrol.R

/**
 * Material theme for Compose content embedded in TrackerControl's existing
 * view hierarchy.
 *
 * DECISION: Hybrid Compose/View screens resolve semantic surface roles from the
 * surrounding Material 3 View theme so their tonal backgrounds remain
 * identical. Dynamic colour remains intentionally disabled to preserve
 * TrackerControl's red/teal identity.
 */
@Composable
fun TrackerControlTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isDarkTheme = (configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val colourScheme = remember(context, isDarkTheme) {
        if (isDarkTheme) {
            trackerControlDarkColours(context)
        } else {
            trackerControlLightColours(context)
        }
    }

    MaterialTheme(
        colorScheme = colourScheme,
        typography = TrackerControlTypography,
        content = content
    )
}

private val TrackerControlTypography = Typography()

internal fun trackerControlLightColours(context: Context) = lightColorScheme(
    primary = context.colour(R.color.colorRedOn),
    onPrimary = Color.White,
    primaryContainer = context.colour(R.color.colorPrimaryLight),
    onPrimaryContainer = Color.Black,
    secondary = context.colour(R.color.colorAccent),
    onSecondary = Color.White,
    secondaryContainer = context.colour(R.color.colorAccent),
    onSecondaryContainer = Color.White,
    background = context.themeColour(android.R.attr.colorBackground),
    onBackground = context.themeColour(com.google.android.material.R.attr.colorOnBackground),
    surface = context.themeColour(com.google.android.material.R.attr.colorSurface),
    onSurface = context.themeColour(com.google.android.material.R.attr.colorOnSurface),
    surfaceVariant = context.themeColour(com.google.android.material.R.attr.colorSurfaceContainer),
    onSurfaceVariant = context.themeColour(com.google.android.material.R.attr.colorOnSurfaceVariant),
    outline = context.themeColour(com.google.android.material.R.attr.colorOutline),
    outlineVariant = context.themeColour(com.google.android.material.R.attr.colorOutlineVariant),
    error = context.colour(R.color.colorRedOn),
    onError = Color.White
)

internal fun trackerControlDarkColours(context: Context) = darkColorScheme(
    // colorRedOn is deliberately brighter in values-night so small actions and
    // status labels retain contrast on the theme-derived dark surface.
    primary = context.colour(R.color.colorRedOn),
    onPrimary = Color.Black,
    primaryContainer = context.colour(R.color.colorPrimaryDark),
    onPrimaryContainer = Color.White,
    secondary = context.colour(R.color.colorAccent),
    onSecondary = Color.Black,
    secondaryContainer = context.colour(R.color.colorAccent),
    onSecondaryContainer = Color.Black,
    background = context.themeColour(android.R.attr.colorBackground),
    onBackground = context.themeColour(com.google.android.material.R.attr.colorOnBackground),
    surface = context.themeColour(com.google.android.material.R.attr.colorSurface),
    onSurface = context.themeColour(com.google.android.material.R.attr.colorOnSurface),
    surfaceVariant = context.themeColour(com.google.android.material.R.attr.colorSurfaceContainer),
    onSurfaceVariant = context.themeColour(com.google.android.material.R.attr.colorOnSurfaceVariant),
    outline = context.themeColour(com.google.android.material.R.attr.colorOutline),
    outlineVariant = context.themeColour(com.google.android.material.R.attr.colorOutlineVariant),
    error = context.colour(R.color.colorRedOn),
    onError = Color.Black
)

private fun Context.themeColour(@AttrRes attribute: Int): Color {
    val value = TypedValue()
    check(theme.resolveAttribute(attribute, value, true)) {
        "Required theme colour attribute $attribute is missing"
    }

    return when {
        value.resourceId != 0 -> Color(ContextCompat.getColor(this, value.resourceId))
        value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT ->
            Color(value.data)
        else -> error("Required theme colour attribute $attribute is not a colour")
    }
}

private fun Context.colour(@ColorRes resource: Int): Color =
    Color(ContextCompat.getColor(this, resource))
