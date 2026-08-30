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

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.kollnig.missioncontrol.R

/** Java-facing entry point for the incrementally migrated Data Rights tab. */
object ActionsScreen {
    fun interface ActionHandler {
        fun onAction(actionId: Int)
    }

    @JvmStatic
    fun install(
        composeView: ComposeView,
        showAdSettings: Boolean,
        actionHandler: ActionHandler
    ) {
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        composeView.setContent {
            TrackerControlTheme {
                DataRightsScreen(
                    showAdSettings = showAdSettings,
                    onAction = actionHandler::onAction
                )
            }
        }
    }
}

@Composable
internal fun DataRightsScreen(
    showAdSettings: Boolean,
    onAction: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp)
    ) {
        DataRightsSection(
            title = stringResource(R.string.data_managment),
            description = stringResource(R.string.data_management_description),
            illustration = R.drawable.ic_email
        ) {
            DetailsPrimaryAction(
                text = stringResource(R.string.request_data),
                onClick = { onAction(R.id.btnReqData) }
            )
            DetailsTextAction(
                text = stringResource(R.string.request_deletion),
                onClick = { onAction(R.id.btnReqDeletion) }
            )
        }

        SectionDivider()

        DataRightsSection(
            title = stringResource(R.string.contacts),
            description = stringResource(R.string.contacts_description),
            illustration = R.drawable.ic_megaphone
        ) {
            DetailsPrimaryAction(
                text = stringResource(R.string.contact_developer),
                onClick = { onAction(R.id.btnContactDev) }
            )
            DetailsTextAction(
                text = stringResource(R.string.contact_playstore),
                onClick = { onAction(R.id.btnContactGoogle) }
            )
            DetailsTextAction(
                text = stringResource(R.string.contact_officials),
                onClick = { onAction(R.id.btnContactOfficials) }
            )
        }

        if (showAdSettings) {
            SectionDivider()
            DataRightsSection(
                title = stringResource(R.string.personalised_ads),
                description = stringResource(R.string.personalised_ads_description),
                illustration = R.drawable.ic_advertising
            ) {
                DetailsPrimaryAction(
                    text = stringResource(R.string.ad_settings),
                    onClick = { onAction(R.id.btnAdSettings) }
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun DataRightsScreenPreview() {
    TrackerControlTheme {
        DataRightsScreen(
            showAdSettings = true,
            onAction = {}
        )
    }
}

@Composable
private fun DataRightsSection(
    title: String,
    description: String,
    illustration: Int,
    actions: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        DetailsSectionHeading(text = title)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = description,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Image(
                painter = painterResource(illustration),
                contentDescription = null,
                modifier = Modifier.size(dimensionResource(R.dimen.card_img)),
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary)
            )
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            actions()
        }
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        thickness = 1.dp
    )
}
