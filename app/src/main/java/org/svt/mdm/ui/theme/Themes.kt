package org.svt.mdm.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/**
 * Interface theme catalogue — the client mirror of the server's
 * `app/themes.py`. The operator picks a theme on the server dashboard; agents
 * receive its id on check-in (and can fetch the full token set from
 * `GET /api/theme`) and restyle the app to match.
 *
 * Keep the ids and colours in sync with the server catalogue.
 */
data class AppTheme(
    val id: String,
    val displayName: String,
    val dark: Boolean,
    val font: Font,
    val colorScheme: ColorScheme,
) {
    enum class Font { SYSTEM, MONO, CONDENSED }

    /** Material typography carrying this theme's base font family. */
    fun typography(): Typography {
        val family = when (font) {
            Font.MONO -> FontFamily.Monospace
            // No condensed face ships with the platform; SansSerif is the
            // closest built-in fallback for the LCARS look.
            Font.CONDENSED -> FontFamily.SansSerif
            Font.SYSTEM -> FontFamily.Default
        }
        val base = Typography()
        return base.copy(
            displayLarge = base.displayLarge.copy(fontFamily = family),
            displayMedium = base.displayMedium.copy(fontFamily = family),
            displaySmall = base.displaySmall.copy(fontFamily = family),
            headlineLarge = base.headlineLarge.copy(fontFamily = family),
            headlineMedium = base.headlineMedium.copy(fontFamily = family),
            headlineSmall = base.headlineSmall.copy(fontFamily = family),
            titleLarge = base.titleLarge.copy(fontFamily = family),
            titleMedium = base.titleMedium.copy(fontFamily = family),
            titleSmall = base.titleSmall.copy(fontFamily = family),
            bodyLarge = base.bodyLarge.copy(fontFamily = family),
            bodyMedium = base.bodyMedium.copy(fontFamily = family),
            bodySmall = base.bodySmall.copy(fontFamily = family),
            labelLarge = base.labelLarge.copy(fontFamily = family),
            labelMedium = base.labelMedium.copy(fontFamily = family),
            labelSmall = base.labelSmall.copy(fontFamily = family),
        )
    }
}

private fun hex(value: String): Color = Color(("ff" + value.removePrefix("#")).toLong(16))

/** Build a Material3 [ColorScheme] from the shared theme token set. */
private fun scheme(
    dark: Boolean,
    bg: String,
    panel: String,
    panel2: String,
    text: String,
    muted: String,
    accent: String,
    accentText: String,
    ok: String,
    warn: String,
    danger: String,
    border: String,
): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = hex(accent),
        onPrimary = hex(accentText),
        primaryContainer = hex(panel2),
        onPrimaryContainer = hex(text),
        secondary = hex(accent),
        onSecondary = hex(accentText),
        tertiary = hex(ok),
        background = hex(bg),
        onBackground = hex(text),
        surface = hex(panel),
        onSurface = hex(text),
        surfaceVariant = hex(panel2),
        onSurfaceVariant = hex(muted),
        outline = hex(border),
        outlineVariant = hex(border),
        error = hex(danger),
        onError = hex(accentText),
    )
}

private fun appTheme(
    id: String,
    name: String,
    dark: Boolean,
    font: AppTheme.Font,
    bg: String,
    panel: String,
    panel2: String,
    text: String,
    muted: String,
    accent: String,
    accentText: String,
    ok: String,
    warn: String,
    danger: String,
    border: String,
): AppTheme = AppTheme(
    id = id, displayName = name, dark = dark, font = font,
    colorScheme = scheme(dark, bg, panel, panel2, text, muted, accent, accentText, ok, warn, danger, border),
)

object Themes {
    val DEFAULT_ID = "midnight"

    // Catalogue order mirrors the server.
    private val catalog: List<AppTheme> = listOf(
        appTheme(
            "midnight", "Midnight", dark = true, font = AppTheme.Font.SYSTEM,
            bg = "#0f1419", panel = "#1a2027", panel2 = "#222a33", text = "#e6edf3",
            muted = "#8b949e", accent = "#2f81f7", accentText = "#ffffff",
            ok = "#2ea043", warn = "#d29922", danger = "#f85149", border = "#30363d",
        ),
        appTheme(
            "graphite", "Graphite", dark = true, font = AppTheme.Font.SYSTEM,
            bg = "#131417", panel = "#1c1e22", panel2 = "#26292f", text = "#e8e8ea",
            muted = "#9096a0", accent = "#b0b8c4", accentText = "#131417",
            ok = "#57a05a", warn = "#c99a3a", danger = "#d9615a", border = "#2e323a",
        ),
        appTheme(
            "nord", "Nord", dark = true, font = AppTheme.Font.SYSTEM,
            bg = "#2e3440", panel = "#3b4252", panel2 = "#434c5e", text = "#eceff4",
            muted = "#aab2c0", accent = "#88c0d0", accentText = "#2e3440",
            ok = "#a3be8c", warn = "#ebcb8b", danger = "#bf616a", border = "#4c566a",
        ),
        appTheme(
            "nebula", "Nebula", dark = true, font = AppTheme.Font.SYSTEM,
            bg = "#14121f", panel = "#1e1b2e", panel2 = "#2a2640", text = "#ece9f5",
            muted = "#a49fc0", accent = "#8b5cf6", accentText = "#ffffff",
            ok = "#34d399", warn = "#fbbf24", danger = "#fb7185", border = "#332d4d",
        ),
        appTheme(
            "terminal", "Terminal", dark = true, font = AppTheme.Font.MONO,
            bg = "#0a140e", panel = "#10201a", panel2 = "#182b22", text = "#d7f5e3",
            muted = "#7fae93", accent = "#4ade80", accentText = "#04120b",
            ok = "#22c55e", warn = "#eab308", danger = "#ef4444", border = "#24483a",
        ),
        appTheme(
            "aurora", "Aurora", dark = false, font = AppTheme.Font.SYSTEM,
            bg = "#f6f8fa", panel = "#ffffff", panel2 = "#eef1f4", text = "#1f2328",
            muted = "#656d76", accent = "#0969da", accentText = "#ffffff",
            ok = "#1a7f37", warn = "#9a6700", danger = "#cf222e", border = "#d0d7de",
        ),
        appTheme(
            "solar", "Solar", dark = false, font = AppTheme.Font.SYSTEM,
            bg = "#fdf6e3", panel = "#eee8d5", panel2 = "#e7e0cc", text = "#073642",
            muted = "#657b83", accent = "#268bd2", accentText = "#fdf6e3",
            ok = "#859900", warn = "#b58900", danger = "#dc322f", border = "#ddd6c1",
        ),
        appTheme(
            "sandstone", "Sandstone", dark = false, font = AppTheme.Font.SYSTEM,
            bg = "#f4ecd8", panel = "#fffaf0", panel2 = "#efe4cc", text = "#433422",
            muted = "#7c6a4d", accent = "#b5651d", accentText = "#fffaf0",
            ok = "#6a8a3f", warn = "#b9822b", danger = "#b23a2f", border = "#ddcca6",
        ),
        appTheme(
            "blueprint", "Blueprint", dark = false, font = AppTheme.Font.SYSTEM,
            bg = "#f5f8fc", panel = "#ffffff", panel2 = "#e9eff7", text = "#0f1b2d",
            muted = "#5a6b84", accent = "#2563eb", accentText = "#ffffff",
            ok = "#0f7b52", warn = "#9a6a00", danger = "#c62b3f", border = "#d3dfec",
        ),
        appTheme(
            "plainsight", "Plainsight", dark = false, font = AppTheme.Font.SYSTEM,
            bg = "#fafafa", panel = "#ffffff", panel2 = "#f4f4f5", text = "#111113",
            muted = "#6b6b73", accent = "#4f46e5", accentText = "#ffffff",
            ok = "#157f3d", warn = "#8a5a00", danger = "#c1121f", border = "#e2e2e6",
        ),
        appTheme(
            "sprout", "Sprout", dark = true, font = AppTheme.Font.SYSTEM,
            bg = "#121417", panel = "#1b1e22", panel2 = "#24282d", text = "#e6e8ea",
            muted = "#9aa0a6", accent = "#3ddc84", accentText = "#06210f",
            ok = "#2eb872", warn = "#ffc107", danger = "#ff5252", border = "#2f343a",
        ),
        appTheme(
            "lcars", "LCARS", dark = true, font = AppTheme.Font.CONDENSED,
            bg = "#000000", panel = "#0b0b0b", panel2 = "#161616", text = "#ffcc99",
            muted = "#cc99cc", accent = "#ff9900", accentText = "#000000",
            ok = "#99cc99", warn = "#ffcc00", danger = "#cc6666", border = "#ff9900",
        ),
    )

    private val byId: Map<String, AppTheme> = catalog.associateBy { it.id }

    /** Resolve a theme id, falling back to the default for unknown/empty ids. */
    fun get(id: String?): AppTheme = byId[id] ?: byId.getValue(DEFAULT_ID)
}
