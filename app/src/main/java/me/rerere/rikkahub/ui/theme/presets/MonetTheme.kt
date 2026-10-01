package me.rerere.rikkahub.ui.theme.presets

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import dynamiccolor.ColorSpec.SpecVersion
import dynamiccolor.ColorSpecs
import dynamiccolor.DynamicScheme
import dynamiccolor.Variant
import hct.Hct
import me.rerere.material3.toColorScheme
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.theme.PresetTheme
import palettes.TonalPalette

/**
 * Monet 默认配色：AOSP 在系统未提供壁纸取色时使用的内置色板，
 * 即 vivo 等不支持动态取色的 ROM 上 dynamicColorScheme 的返回值。
 * 五条色板种子取自 android.system.customization.ColorPalette.DEFAULT_SEEDS。
 */
val MonetThemePreset by lazy {
    PresetTheme(
        id = "monet",
        name = {
            Text(stringResource(id = R.string.theme_name_monet))
        },
        standardLight = buildScheme(dark = false),
        standardDark = buildScheme(dark = true),
    )
}

private const val SEED_ACCENT1 = 0xFF0061A4
private const val SEED_ACCENT2 = 0xFF535F70
private const val SEED_ACCENT3 = 0xFF6B5778
private const val SEED_NEUTRAL1 = 0xFF1A1C1E
private const val SEED_NEUTRAL2 = 0xFF44474E

private fun buildScheme(dark: Boolean): ColorScheme {
    val sourceHct = Hct.fromInt(SEED_ACCENT1.toInt())
    val specVersion = SpecVersion.SPEC_2021
    val platform = DynamicScheme.DEFAULT_PLATFORM
    val contrastLevel = 0.0
    val colorSpec = ColorSpecs.get(specVersion)

    val scheme = DynamicScheme(
        sourceHct,
        Variant.TONAL_SPOT,
        dark,
        contrastLevel,
        platform,
        specVersion,
        TonalPalette.fromInt(SEED_ACCENT1.toInt()),
        TonalPalette.fromInt(SEED_ACCENT2.toInt()),
        TonalPalette.fromInt(SEED_ACCENT3.toInt()),
        TonalPalette.fromInt(SEED_NEUTRAL1.toInt()),
        TonalPalette.fromInt(SEED_NEUTRAL2.toInt()),
        colorSpec.getErrorPalette(Variant.TONAL_SPOT, sourceHct, dark, platform, contrastLevel),
    )
    return scheme.toColorScheme()
}
