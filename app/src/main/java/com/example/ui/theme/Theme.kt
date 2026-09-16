package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = GhDarkAccentBlue,
    onPrimary = GhDarkBackground,
    primaryContainer = GhDarkSurfaceVariant,
    onPrimaryContainer = GhDarkTextPrimary,
    secondary = GhDarkAccentGreen,
    onSecondary = GhDarkBackground,
    background = GhDarkBackground,
    onBackground = GhDarkTextPrimary,
    surface = GhDarkSurface,
    onSurface = GhDarkTextPrimary,
    surfaceVariant = GhDarkSurfaceVariant,
    onSurfaceVariant = GhDarkTextSecondary,
    outline = GhDarkBorder,
    error = GhDarkAccentRed
)

private val LightColorScheme = lightColorScheme(
    primary = GhLightAccentBlue,
    onPrimary = GhLightSurface,
    primaryContainer = GhLightSurfaceVariant,
    onPrimaryContainer = GhLightTextPrimary,
    secondary = GhLightAccentGreen,
    onSecondary = GhLightSurface,
    background = GhLightBackground,
    onBackground = GhLightTextPrimary,
    surface = GhLightSurface,
    onSurface = GhLightTextPrimary,
    surfaceVariant = GhLightSurfaceVariant,
    onSurfaceVariant = GhLightTextSecondary,
    outline = GhLightBorder,
    error = GhLightAccentRed
)

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Dynamic color is available on Android 12+
  dynamicColor: Boolean = true,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
