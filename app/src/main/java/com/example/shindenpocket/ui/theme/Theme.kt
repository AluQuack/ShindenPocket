package com.example.shindenpocket.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = PocketPalette.primary,
    onPrimary = Color.White,
    primaryContainer = PocketPalette.primaryContainer,
    onPrimaryContainer = PocketPalette.onPrimaryContainer,
    secondary = PocketPalette.primarySoft,
    onSecondary = PocketPalette.background,
    secondaryContainer = PocketPalette.surfaceVariant,
    onSecondaryContainer = PocketPalette.onBackground,
    tertiary = PocketPalette.success,
    onTertiary = PocketPalette.background,
    tertiaryContainer = PocketPalette.success.copy(alpha = 0.16f),
    onTertiaryContainer = PocketPalette.success,
    background = PocketPalette.background,
    onBackground = PocketPalette.onBackground,
    surface = PocketPalette.surface,
    onSurface = PocketPalette.onBackground,
    surfaceVariant = PocketPalette.surfaceVariant,
    onSurfaceVariant = PocketPalette.onSurfaceVariant,
    surfaceContainerLowest = PocketPalette.background,
    surfaceContainerLow = PocketPalette.surface,
    surfaceContainer = PocketPalette.surfaceRaised,
    surfaceContainerHigh = PocketPalette.surfaceVariant,
    surfaceContainerHighest = PocketPalette.surfaceHighest,
    error = PocketPalette.error,
    errorContainer = Color(0xFF4B2529),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = PocketPalette.outline,
    outlineVariant = PocketPalette.outlineVariant,
    surfaceTint = PocketPalette.primary
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF7138B5),
    secondary = Color(0xFF66547F),
    tertiary = Color(0xFF276B55)
)

private val PocketShapes = Shapes(
    extraSmall = RoundedCornerShape(PocketRadius.small.dp),
    small = RoundedCornerShape(PocketRadius.control.dp),
    medium = RoundedCornerShape(PocketRadius.card.dp),
    large = RoundedCornerShape(PocketRadius.large.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun ShindenPocketTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = PocketShapes,
        content = content
    )
}
