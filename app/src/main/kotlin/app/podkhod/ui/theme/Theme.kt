package app.podkhod.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// High-contrast dark palette: the gym is loud and rushed, so text must stay readable at arm's length.
private val PodkhodDarkColors = darkColorScheme(
    primary = Color(0xFFFFC247),
    onPrimary = Color(0xFF1A1200),
    primaryContainer = Color(0xFF3D2E00),
    onPrimaryContainer = Color(0xFFFFE2A6),
    secondary = Color(0xFF7CD4FF),
    onSecondary = Color(0xFF00222F),
    secondaryContainer = Color(0xFF0D3446),
    onSecondaryContainer = Color(0xFFC6EBFF),
    background = Color(0xFF0E0F12),
    onBackground = Color(0xFFF3F3F5),
    surface = Color(0xFF0E0F12),
    onSurface = Color(0xFFF3F3F5),
    surfaceVariant = Color(0xFF1F2229),
    onSurfaceVariant = Color(0xFFCDD0D7),
    surfaceContainerLowest = Color(0xFF0A0B0D),
    surfaceContainerLow = Color(0xFF14161A),
    surfaceContainer = Color(0xFF181A1F),
    surfaceContainerHigh = Color(0xFF1F2228),
    surfaceContainerHighest = Color(0xFF272A31),
    outline = Color(0xFF8C919B),
    outlineVariant = Color(0xFF3A3E46),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2B0000),
)

private val Base = Typography()

private val PodkhodTypography = Typography(
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.Bold),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.Bold),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge.copy(fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = Base.bodyMedium.copy(fontSize = 16.sp, lineHeight = 22.sp),
    labelLarge = Base.labelLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun PodkhodTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PodkhodDarkColors,
        typography = PodkhodTypography,
        content = content,
    )
}
