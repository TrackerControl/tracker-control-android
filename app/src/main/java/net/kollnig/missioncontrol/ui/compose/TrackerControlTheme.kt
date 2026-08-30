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
import androidx.annotation.ColorRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import net.kollnig.missioncontrol.R

/**
 * Material theme for Compose content embedded in TrackerControl's existing
 * view hierarchy.
 *
 * Colours are resolved from Android resources so the existing values-night
 * palette remains the source of truth. Dynamic colour is intentionally not
 * used: the app's red/teal identity and neutral detail surfaces must match
 * the surrounding View-based screens.
 */
@Composable
fun TrackerControlTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val isDarkTheme = (context.resources.configuration.uiMode and
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

private fun trackerControlLightColours(context: Context) = lightColorScheme(
    primary = context.colour(R.color.colorRedOn),
    onPrimary = Color.White,
    primaryContainer = context.colour(R.color.colorPrimaryLight),
    onPrimaryContainer = Color.Black,
    secondary = context.colour(R.color.colorAccent),
    onSecondary = Color.White,
    secondaryContainer = context.colour(R.color.colorAccent),
    onSecondaryContainer = Color.White,
    background = context.colour(R.color.trackerFeedBackground),
    onBackground = Color.Black,
    surface = context.colour(R.color.trackerFeedBackground),
    onSurface = Color.Black,
    surfaceVariant = context.colour(R.color.trackerFeedSectionBackground),
    onSurfaceVariant = Color(0xFF424242),
    outline = context.colour(R.color.trackerFeedDivider),
    outlineVariant = context.colour(R.color.trackerFeedDivider),
    error = context.colour(R.color.colorRedOn),
    onError = Color.White
)

private fun trackerControlDarkColours(context: Context) = darkColorScheme(
    // colorRedOn is deliberately brighter in values-night so small actions and
    // status labels retain contrast on the black detail-screen background.
    primary = context.colour(R.color.colorRedOn),
    onPrimary = Color.Black,
    primaryContainer = context.colour(R.color.colorPrimaryDark),
    onPrimaryContainer = Color.White,
    secondary = context.colour(R.color.colorAccent),
    onSecondary = Color.Black,
    secondaryContainer = context.colour(R.color.colorAccent),
    onSecondaryContainer = Color.Black,
    background = context.colour(R.color.trackerFeedBackground),
    onBackground = Color.White,
    surface = context.colour(R.color.trackerFeedBackground),
    onSurface = Color.White,
    surfaceVariant = context.colour(R.color.trackerFeedSectionBackground),
    onSurfaceVariant = Color(0xFFE0E0E0),
    outline = context.colour(R.color.trackerFeedDivider),
    outlineVariant = context.colour(R.color.trackerFeedDivider),
    error = context.colour(R.color.colorRedOn),
    onError = Color.Black
)

private fun Context.colour(@ColorRes resource: Int): Color =
    Color(ContextCompat.getColor(this, resource))
