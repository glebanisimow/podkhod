package app.podkhod.ui.format

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * The UI is Russian-only, but Android picks the plural form by the system locale: on an English phone
 * "9 упражнений" would use the English "other" rule. Quantities are therefore always resolved with Russian rules.
 */
fun Context.russianResources(): Resources {
    val config = Configuration(resources.configuration).apply { setLocale(Locale.forLanguageTag("ru")) }
    return createConfigurationContext(config).resources
}

@Composable
fun ruPlural(@PluralsRes id: Int, count: Int): String {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val res = remember(configuration) { context.russianResources() }
    return res.getQuantityString(id, count, count)
}
