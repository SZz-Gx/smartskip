package io.github.szzgx.smartskip.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * SmartSkip's colour scheme.
 *
 * Deliberately restrained: this app's job is to stay out of the way, so the palette is a
 * calm blue-grey with semantic colours reserved for safety states. Green means "acted", amber
 * means "observed but did not act", red means "a dangerous mis-click was recorded".
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF1B4B8F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF001B3D),
    secondary = Color(0xFF565E71),
    secondaryContainer = Color(0xFFDAE2F9),
    onSecondaryContainer = Color(0xFF131C2B),
    tertiary = Color(0xFF6F5575),
    background = Color(0xFFFDFBFF),
    surface = Color(0xFFFDFBFF),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44474F),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF003062),
    primaryContainer = Color(0xFF00458A),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFBEC6DC),
    secondaryContainer = Color(0xFF3E4759),
    onSecondaryContainer = Color(0xFFDAE2F9),
    tertiary = Color(0xFFDCBCE1),
    background = Color(0xFF1A1B1F),
    surface = Color(0xFF1A1B1F),
    surfaceVariant = Color(0xFF44474F),
    onSurfaceVariant = Color(0xFFC4C6D0),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/** Semantic colours for decision outcomes, shared by every screen that reports one. */
object StatusColors {
    /** An automatic skip was dispatched. */
    val acted = Color(0xFF1B6B3A)
    val actedContainer = Color(0xFFD5F2DF)

    /** Observed, scored, deliberately not acted on. */
    val observed = Color(0xFF8A5A00)
    val observedContainer = Color(0xFFFFEBC7)

    /** A hard veto refused the control. */
    val vetoed = Color(0xFF7A4E00)
    val vetoedContainer = Color(0xFFFFF0D6)

    /** A dangerous mis-click was recorded, or the app is suspended. */
    val danger = Color(0xFFBA1A1A)
    val dangerContainer = Color(0xFFFFDAD6)
}

@Composable
fun SmartSkipTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /**
     * Android 12's dynamic colour is used when available, so the app matches the user's
     * wallpaper like every other app on the device.
     */
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(colorScheme = colors, content = content)
}
