package com.openlink.child.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = OpenLinkBlue,
    background = OpenLinkBackground,
    error = OpenLinkError
)

private val DarkColors = darkColorScheme(
    primary = OpenLinkBlueDark,
    background = OpenLinkBackgroundDark,
    error = OpenLinkError
)

/** MVP-simple theming: system dark/light + Material You dynamic color on API 31+, nothing
 *  fancier. Wraps the whole Compose tree from MainActivity. */
@Composable
fun OpenLinkTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
