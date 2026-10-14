package io.github.whyater.wristusage.phone.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.whyater.wristusage.domain.Severity

/** Same colours as the watch app: black background, restrained provider accents, amber and red for high usage. */
object Hues {
    val Background = Color(0xFF000000)
    val TextPrimary = Color(0xFFF2F2F2)
    val TextSecondary = Color(0xFFB4B4B8)
    val TextTertiary = Color(0xFF8A8A90)
    val Track = Color(0xFF26262A)
    val Surface = Color(0xFF121214)
    val Warning = Color(0xFFFFB224)
    val Critical = Color(0xFFFF5C57)
    val Good = Color(0xFF8FD19E)

    private val Claude = Color(0xFFE07A5F)
    private val Codex = Color(0xFFD7DCE6)
    private val Other = Color(0xFF8FB4E8)

    fun accent(providerId: String): Color = when (providerId.lowercase()) {
        "claude" -> Claude
        "codex" -> Codex
        else -> Other
    }

    fun bar(severity: Severity, accent: Color): Color = when (severity) {
        Severity.Normal -> accent
        Severity.Warning -> Warning
        Severity.Critical -> Critical
    }

    fun percentText(severity: Severity): Color = when (severity) {
        Severity.Normal -> TextPrimary
        Severity.Warning -> Warning
        Severity.Critical -> Critical
    }
}

/** Digits of equal width so percentages do not jitter as they change. */
private const val TABULAR = "tnum"

object PhoneType {
    val Percent = TextStyle(fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = TABULAR)
    val Overline = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.2.sp)
}

private val Type = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = TABULAR),
        bodyMedium = base.bodyMedium.copy(fontFeatureSettings = TABULAR),
        bodySmall = base.bodySmall.copy(fontFeatureSettings = TABULAR),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium),
    )
}

@Composable
fun PhoneTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = Color(0xFFE6E6EA),
        onPrimary = Color.Black,
        primaryContainer = Color(0xFF2A2A2E),
        onPrimaryContainer = Hues.TextPrimary,
        secondary = Hues.TextSecondary,
        background = Hues.Background,
        onBackground = Hues.TextPrimary,
        surface = Hues.Background,
        onSurface = Hues.TextPrimary,
        onSurfaceVariant = Hues.TextSecondary,
        surfaceContainer = Hues.Surface,
        surfaceContainerHigh = Hues.Surface,
        outline = Color(0xFF55555B),
        outlineVariant = Hues.Track,
        error = Hues.Critical,
        onError = Color.Black,
    )
    MaterialTheme(colorScheme = colors, typography = Type, content = content)
}
