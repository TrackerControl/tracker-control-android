/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.kollnig.missioncontrol.ui.compose

import android.graphics.Picture
import android.graphics.drawable.PictureDrawable
import android.view.View
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import net.kollnig.missioncontrol.R

internal sealed interface CountriesMapState {
    data object Loading : CountriesMapState
    data class Loaded(val picture: Picture, val countryCodes: String) : CountriesMapState
    data object Failed : CountriesMapState
}

class CountriesScreenController internal constructor(
    private val state: MutableState<CountriesMapState>
) {
    fun showMap(picture: Picture, countryCodes: String) {
        state.value = CountriesMapState.Loaded(picture, countryCodes)
    }

    fun showFailure() {
        state.value = CountriesMapState.Failed
    }
}

/** Java-facing entry point for the incrementally migrated Countries tab. */
object CountriesScreen {
    @JvmStatic
    fun install(composeView: ComposeView): CountriesScreenController {
        val state = mutableStateOf<CountriesMapState>(CountriesMapState.Loading)
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        composeView.setContent {
            TrackerControlTheme {
                CountriesScreenContent(state.value)
            }
        }
        return CountriesScreenController(state)
    }
}

@Composable
internal fun CountriesScreenContent(state: CountriesMapState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        DetailsSectionHeading(text = stringResource(R.string.countries))
        Text(
            text = stringResource(R.string.countries_explanation),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            when (state) {
                CountriesMapState.Loading -> CircularProgressIndicator()
                CountriesMapState.Failed -> Text(
                    text = stringResource(R.string.countries_loading_failed),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                is CountriesMapState.Loaded -> CountryMap(state)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun CountriesScreenPreview() {
    TrackerControlTheme {
        CountriesScreenContent(CountriesMapState.Failed)
    }
}

@Composable
private fun CountryMap(state: CountriesMapState.Loaded) {
    val description = if (state.countryCodes.isEmpty()) {
        stringResource(R.string.countries_map_none)
    } else {
        stringResource(R.string.countries_map_highlighted, state.countryCodes)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = description }
    ) {
        AndroidView(
            factory = { context ->
                ImageView(context).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
            },
            update = { imageView ->
                imageView.setImageDrawable(PictureDrawable(state.picture))
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
