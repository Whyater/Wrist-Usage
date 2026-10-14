package io.github.whyater.wristusage.domain

import io.github.whyater.wristusage.data.Provider

/** Colors as ARGB ints, shared by the tile (protolayout) and the app (Compose). Black background for OLED. */
object Palette {
    const val BACKGROUND = 0xFF000000.toInt()
    const val TEXT_PRIMARY = 0xFFF2F2F2.toInt()
    const val TEXT_SECONDARY = 0xFFB4B4B8.toInt()
    const val TEXT_TERTIARY = 0xFF8A8A90.toInt()
    const val TRACK = 0xFF26262A.toInt()
    const val SURFACE = 0xFF121214.toInt()

    const val WARNING = 0xFFFFB224.toInt()
    const val CRITICAL = 0xFFFF5C57.toInt()

    private const val CLAUDE = 0xFFE07A5F.toInt()
    private const val CODEX = 0xFFD7DCE6.toInt()
    private const val OTHER = 0xFF8FB4E8.toInt()

    fun accent(provider: Provider): Int = accent(provider.id)

    fun accent(providerId: String): Int = when (providerId.lowercase()) {
        "claude" -> CLAUDE
        "codex" -> CODEX
        else -> OTHER
    }

    /** Bar color: severity colors override the provider accent once usage is high. */
    fun bar(severity: Severity, accent: Int): Int = when (severity) {
        Severity.Normal -> accent
        Severity.Warning -> WARNING
        Severity.Critical -> CRITICAL
    }

    /** Percent text stays white until it needs attention. */
    fun percentText(severity: Severity): Int = when (severity) {
        Severity.Normal -> TEXT_PRIMARY
        Severity.Warning -> WARNING
        Severity.Critical -> CRITICAL
    }
}
