package app.podkhod.ui.theme

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The brand code of the "дед и медведь" badge: two flat colours, ink and amber, and hard geometric
 * edges. Everything else is derived from those two: surfaces are ink lifted with a warm tint, text is a
 * warm off-white, the second accent is a lighter amber. No blue, no gradients.
 */
object Brand {
    val Amber = Color(0xFFFFAA1D)
    val Ink = Color(0xFF0A0A09)
}

// High contrast stays the rule: the gym is loud and rushed, text must read at arm's length.
private val PodkhodDarkColors = darkColorScheme(
    primary = Brand.Amber,
    onPrimary = Brand.Ink,
    primaryContainer = Color(0xFF3A2706),
    onPrimaryContainer = Color(0xFFFFD48C),
    inversePrimary = Color(0xFF7A4E00),
    secondary = Color(0xFFF2C77A),
    onSecondary = Brand.Ink,
    // Selected chips and the ± steppers: clearly amber-tinted, still a step below the primary amber.
    secondaryContainer = Color(0xFF5C3F0C),
    onSecondaryContainer = Color(0xFFFFE0A8),
    tertiary = Color(0xFFE9DFC9),
    onTertiary = Brand.Ink,
    background = Brand.Ink,
    onBackground = Color(0xFFF5F1E8),
    surface = Brand.Ink,
    onSurface = Color(0xFFF5F1E8),
    surfaceVariant = Color(0xFF24221D),
    onSurfaceVariant = Color(0xFFC9C2B2),
    surfaceTint = Brand.Amber,
    surfaceContainerLowest = Color(0xFF060605),
    surfaceContainerLow = Color(0xFF121210),
    surfaceContainer = Color(0xFF171714),
    surfaceContainerHigh = Color(0xFF1E1D19),
    surfaceContainerHighest = Color(0xFF272520),
    inverseSurface = Color(0xFFF5F1E8),
    inverseOnSurface = Brand.Ink,
    outline = Color(0xFF8E8676),
    outlineVariant = Color(0xFF3A372F),
    scrim = Color(0xFF000000),
    error = Color(0xFFFF6B57),
    onError = Brand.Ink,
    errorContainer = Color(0xFF4A140C),
    onErrorContainer = Color(0xFFFFDAD3),
)

private val Base = Typography()

// Poster weights: headlines are heavy and tight like the badge silhouettes, body text stays regular and large.
private val PodkhodTypography = Typography(
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
    titleLarge = Base.titleLarge.copy(fontSize = 24.sp, fontWeight = FontWeight.ExtraBold),
    titleMedium = Base.titleMedium.copy(fontSize = 19.sp, fontWeight = FontWeight.Bold),
    bodyLarge = Base.bodyLarge.copy(fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = Base.bodyMedium.copy(fontSize = 16.sp, lineHeight = 22.sp),
    labelLarge = Base.labelLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp),
    labelMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
)

// Hard edges with just enough rounding not to look broken on a phone screen.
private val PodkhodShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(3.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(6.dp),
    extraLarge = RoundedCornerShape(8.dp),
)

/** Material buttons are pills regardless of the theme; ours are bevelled plates like the badge's facets. */
object BrandShapes {
    val Button = CutCornerShape(8.dp)
}

@Composable
fun PodkhodTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PodkhodDarkColors,
        typography = PodkhodTypography,
        shapes = PodkhodShapes,
        content = content,
    )
}
