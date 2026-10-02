package app.podkhod.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.podkhod.BuildConfig
import app.podkhod.R
import app.podkhod.ui.components.ScreenHeader
import app.podkhod.ui.components.SectionCard

@Composable
fun AboutScreen(onBack: () -> Unit, onOpenLicenses: () -> Unit) {
    Column(Modifier.fillMaxSize().testTag("screen_about")) {
        ScreenHeader(stringResource(R.string.about_title), onBack = onBack)
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 4.dp).testTag("about_version"),
            )
            InfoCard(stringResource(R.string.about_disclaimer_title), stringResource(R.string.about_disclaimer))
            InfoCard(stringResource(R.string.about_privacy_title), stringResource(R.string.about_privacy))
            InfoCard(stringResource(R.string.about_licenses_title), stringResource(R.string.about_licenses_body))
            InfoCard(
                stringResource(R.string.about_media_licenses),
                stringResource(R.string.about_media_licenses_hint),
                Modifier.clickable(onClick = onOpenLicenses).testTag("about_media_licenses"),
            )
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String, modifier: Modifier = Modifier) {
    SectionCard(modifier) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
