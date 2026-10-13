package io.github.whyater.wristusage.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import io.github.whyater.wristusage.domain.Palette

/** Black background, soft white text, one restrained accent per provider (see [Palette]). */
@Composable
fun UsageTheme(content: @Composable () -> Unit) {
    val colors = ColorScheme(
        background = Color(Palette.BACKGROUND),
        onBackground = Color(Palette.TEXT_PRIMARY),
        surfaceContainer = Color(Palette.SURFACE),
        surfaceContainerLow = Color(Palette.SURFACE),
        surfaceContainerHigh = Color(Palette.SURFACE),
        onSurface = Color(Palette.TEXT_PRIMARY),
        onSurfaceVariant = Color(Palette.TEXT_SECONDARY),
        primary = Color(0xFFE6E6EA),
        onPrimary = Color.Black,
        primaryContainer = Color(0xFF2A2A2E),
        onPrimaryContainer = Color(Palette.TEXT_PRIMARY),
        secondaryContainer = Color(0xFF2A2A2E),
        onSecondaryContainer = Color(Palette.TEXT_PRIMARY),
        outline = Color(Palette.TRACK),
        outlineVariant = Color(Palette.TRACK),
        error = Color(Palette.CRITICAL),
    )
    MaterialTheme(colorScheme = colors, content = content)
}
