package app.podkhod.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podkhod.R
import app.podkhod.data.catalog.CatalogRepository
import app.podkhod.data.media.MediaRepository
import app.podkhod.ui.components.ScreenHeader
import app.podkhod.ui.components.SectionCard
import org.koin.compose.koinInject

@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val media = koinInject<MediaRepository>()
    val catalog = koinInject<CatalogRepository>()
    val exercises by catalog.exercisesById.collectAsStateWithLifecycle(emptyMap())
    val userFiles by media.userFiles.collectAsStateWithLifecycle()
    val manifest = media.manifest
    val sorted = exercises.values.sortedBy { it.nameRu }
    val covered = sorted.count { manifest.items.containsKey(it.id) || userFiles.containsKey(it.id) }

    Column(Modifier.fillMaxSize().testTag("screen_licenses")) {
        ScreenHeader(stringResource(R.string.licenses_title), onBack = onBack)
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.licenses_coverage, covered, sorted.size),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 4.dp).testTag("licenses_coverage"),
                )
            }
            items(manifest.sources, key = { it.id }) { s ->
                SectionCard {
                    Text(s.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.licenses_source, s.url, s.author, s.license, s.licenseUrl, s.commit?.take(12) ?: "—"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                SectionCard {
                    Text(stringResource(R.string.licenses_similar_note), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.licenses_own_files), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(sorted, key = { it.id }) { ex ->
                val item = manifest.items[ex.id]
                val status = when {
                    userFiles.containsKey(ex.id) -> stringResource(R.string.licenses_user_file)
                    item == null -> stringResource(R.string.licenses_no_media)
                    else -> (if (item.match == MediaRepository.MATCH_SIMILAR) "≈ " else "") + "${item.sourceId} · ${item.license}"
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
                    Text(ex.nameRu, style = MaterialTheme.typography.bodyLarge)
                    Text(ex.id, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
        }
    }
}
