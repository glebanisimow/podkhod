package app.podkhod.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import app.podkhod.platform.AppInfo
import org.koin.compose.koinInject
import app.podkhod.resources.*
import app.podkhod.ui.components.BrandBadge
import app.podkhod.ui.components.ScreenHeader
import app.podkhod.ui.components.SectionCard

@Composable
fun AboutScreen(onBack: () -> Unit, onOpenLicenses: () -> Unit) {
    Column(Modifier.fillMaxSize().testTag("screen_about")) {
        ScreenHeader(stringResource(Res.string.about_title), onBack = onBack)
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                BrandBadge(Modifier.size(160.dp).testTag("about_logo"))
                Text(
                    stringResource(Res.string.app_name),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    koinInject<AppInfo>().let { stringResource(Res.string.about_version, it.versionName, it.build) },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("about_version"),
                )
            }
            InfoCard(stringResource(Res.string.about_disclaimer_title), stringResource(Res.string.about_disclaimer))
            InfoCard(stringResource(Res.string.about_privacy_title), stringResource(Res.string.about_privacy))
            InfoCard(stringResource(Res.string.about_licenses_title), stringResource(Res.string.about_licenses_body))
            InfoCard(
                stringResource(Res.string.about_media_licenses),
                stringResource(Res.string.about_media_licenses_hint),
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
