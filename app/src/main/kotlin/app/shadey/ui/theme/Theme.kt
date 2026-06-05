package app.shadey.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFFF5A623),
    onPrimary = Color(0xFF241A00),
    secondary = Color(0xFF3D5A80),
    background = Color(0xFFECEFF3),
    surface = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFC65C),
    onPrimary = Color(0xFF2A1E00),
    secondary = Color(0xFF98C1D9),
    background = Color(0xFF111418),
    surface = Color(0xFF1A1E25),
)

/** Sunny amber accents on a calm sky-grey surface. */
@Composable
fun ShadeyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
