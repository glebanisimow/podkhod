package app.podkhod.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.podkhod.resources.*

@Composable
fun ScreenHeader(title: String, onBack: (() -> Unit)? = null) {
    Column {
        HeaderRow(title, onBack)
        // Top-level screens get the "зубы" line under the title; inner screens stay plain.
        if (onBack == null) ZigzagLine(Modifier.padding(horizontal = 20.dp), MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
    }
}

@Composable
private fun HeaderRow(title: String, onBack: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = if (onBack == null) 20.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(painterResource(Res.drawable.ic_back), contentDescription = stringResource(Res.string.back))
            }
        } else {
            // Top-level screens carry the badge, like a stamp on every page of the log.
            BrandBadge(Modifier.size(36.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
}

/**
 * The round "дед и медведь" badge in its on-dark form (amber ring: the black one vanishes on ink);
 * decorative, so screen readers skip it.
 */
@Composable
fun BrandBadge(modifier: Modifier = Modifier) {
    Image(painterResource(Res.drawable.logo_krug_on_dark), contentDescription = null, modifier = modifier)
}

/** The night scene in amber, for first-run and empty screens. */
@Composable
fun BrandScene(modifier: Modifier = Modifier, height: Dp = 180.dp) {
    Image(
        painterResource(Res.drawable.art_noch),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier.fillMaxWidth().height(height),
    )
}

/** The scene's thin horizon line, as a divider in the brand style. */
@Composable
fun Horizon(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.primary))
}


@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}
