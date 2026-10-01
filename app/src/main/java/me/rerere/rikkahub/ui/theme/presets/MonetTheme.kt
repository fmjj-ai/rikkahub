package me.rerere.rikkahub.ui.theme.presets

import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.theme.PresetTheme

/**
 * Monet 默认配色：AOSP 在系统未提供壁纸取色时使用的内置配色，
 * 即 vivo OriginOS 等不支持动态取色的 ROM 上 dynamicColorScheme 的返回值。
 * 色值取自 AOSP frameworks/base core/res/res/values/colors.xml（Android 14）中的
 * system_*_light / system_*_dark 命名角色色，映射关系与 androidx.compose.material3
 * dynamicLightColorScheme34 / dynamicDarkColorScheme34 一致：
 * inversePrimary = 对侧 primary、inverseSurface/OnSurface = 对侧 surface/onSurface，
 * error 系与 scrim 使用 lightColorScheme/darkColorScheme 基线默认值。
 */
val MonetThemePreset by lazy {
    PresetTheme(
        id = "monet",
        name = {
            Text(stringResource(id = R.string.theme_name_monet))
        },
        standardLight = lightScheme,
        standardDark = darkScheme,
    )
}

private val primaryLight = Color(0xFF445E91)
private val onPrimaryLight = Color(0xFFFFFFFF)
private val primaryContainerLight = Color(0xFFD8E2FF)
private val onPrimaryContainerLight = Color(0xFF001A41)
private val secondaryLight = Color(0xFF575E71)
private val onSecondaryLight = Color(0xFFFFFFFF)
private val secondaryContainerLight = Color(0xFFDBE2F9)
private val onSecondaryContainerLight = Color(0xFF141B2C)
private val tertiaryLight = Color(0xFF715573)
private val onTertiaryLight = Color(0xFFFFFFFF)
private val tertiaryContainerLight = Color(0xFFFBD7FC)
private val onTertiaryContainerLight = Color(0xFF29132D)
private val errorLight = Color(0xFFBA1A1A)
private val onErrorLight = Color(0xFFFFFFFF)
private val errorContainerLight = Color(0xFFFFDAD6)
private val onErrorContainerLight = Color(0xFF410002)
private val backgroundLight = Color(0xFFFAF9FD)
private val onBackgroundLight = Color(0xFF1B1B1F)
private val surfaceLight = Color(0xFFFAF9FD)
private val onSurfaceLight = Color(0xFF1B1B1F)
private val surfaceVariantLight = Color(0xFFE1E2EC)
private val onSurfaceVariantLight = Color(0xFF44474F)
private val outlineLight = Color(0xFF72747D)
private val outlineVariantLight = Color(0xFFC4C7C5)
private val scrimLight = Color(0xFF000000)
private val inverseSurfaceLight = Color(0xFF121316)
private val inverseOnSurfaceLight = Color(0xFFE3E2E6)
private val inversePrimaryLight = Color(0xFFADC6FF)
private val surfaceDimLight = Color(0xFFDBD9DD)
private val surfaceBrightLight = Color(0xFFFAF9FD)
private val surfaceContainerLowestLight = Color(0xFFFFFFFF)
private val surfaceContainerLowLight = Color(0xFFF5F3F7)
private val surfaceContainerLight = Color(0xFFEFEDF1)
private val surfaceContainerHighLight = Color(0xFFE9E7EC)
private val surfaceContainerHighestLight = Color(0xFFE3E2E6)
private val primaryFixedLight = Color(0xFFD8E2FF)
private val primaryFixedDimLight = Color(0xFFADC6FF)
private val onPrimaryFixedLight = Color(0xFF001A41)
private val onPrimaryFixedVariantLight = Color(0xFF2B4678)
private val secondaryFixedLight = Color(0xFFDBE2F9)
private val secondaryFixedDimLight = Color(0xFFBFC6DC)
private val onSecondaryFixedLight = Color(0xFF141B2C)
private val onSecondaryFixedVariantLight = Color(0xFF3F4759)
private val tertiaryFixedLight = Color(0xFFFBD7FC)
private val tertiaryFixedDimLight = Color(0xFFDEBCDF)
private val onTertiaryFixedLight = Color(0xFF29132D)
private val onTertiaryFixedVariantLight = Color(0xFF583E5B)

private val primaryDark = Color(0xFFADC6FF)
private val onPrimaryDark = Color(0xFF102F60)
private val primaryContainerDark = Color(0xFF2B4678)
private val onPrimaryContainerDark = Color(0xFFD8E2FF)
private val secondaryDark = Color(0xFFBFC6DC)
private val onSecondaryDark = Color(0xFF293041)
private val secondaryContainerDark = Color(0xFF3F4759)
private val onSecondaryContainerDark = Color(0xFFDBE2F9)
private val tertiaryDark = Color(0xFFDEBCDF)
private val onTertiaryDark = Color(0xFF402843)
private val tertiaryContainerDark = Color(0xFF583E5B)
private val onTertiaryContainerDark = Color(0xFFFBD7FC)
private val errorDark = Color(0xFFFFB4AB)
private val onErrorDark = Color(0xFF690005)
private val errorContainerDark = Color(0xFF93000A)
private val onErrorContainerDark = Color(0xFFFFDAD6)
private val backgroundDark = Color(0xFF121316)
private val onBackgroundDark = Color(0xFFE3E2E6)
private val surfaceDark = Color(0xFF121316)
private val onSurfaceDark = Color(0xFFE3E2E6)
private val surfaceVariantDark = Color(0xFF44474F)
private val onSurfaceVariantDark = Color(0xFFC4C6D0)
private val outlineDark = Color(0xFF72747D)
private val outlineVariantDark = Color(0xFF444746)
private val scrimDark = Color(0xFF000000)
private val inverseSurfaceDark = Color(0xFFFAF9FD)
private val inverseOnSurfaceDark = Color(0xFF1B1B1F)
private val inversePrimaryDark = Color(0xFF445E91)
private val surfaceDimDark = Color(0xFF121316)
private val surfaceBrightDark = Color(0xFF38393C)
private val surfaceContainerLowestDark = Color(0xFF0D0E11)
private val surfaceContainerLowDark = Color(0xFF1B1B1F)
private val surfaceContainerDark = Color(0xFF1F1F23)
private val surfaceContainerHighDark = Color(0xFF292A2D)
private val surfaceContainerHighestDark = Color(0xFF343538)
private val primaryFixedDark = Color(0xFFD8E2FF)
private val primaryFixedDimDark = Color(0xFFADC6FF)
private val onPrimaryFixedDark = Color(0xFF001A41)
private val onPrimaryFixedVariantDark = Color(0xFF2B4678)
private val secondaryFixedDark = Color(0xFFDBE2F9)
private val secondaryFixedDimDark = Color(0xFFBFC6DC)
private val onSecondaryFixedDark = Color(0xFF141B2C)
private val onSecondaryFixedVariantDark = Color(0xFF3F4759)
private val tertiaryFixedDark = Color(0xFFFBD7FC)
private val tertiaryFixedDimDark = Color(0xFFDEBCDF)
private val onTertiaryFixedDark = Color(0xFF29132D)
private val onTertiaryFixedVariantDark = Color(0xFF583E5B)

private val lightScheme = lightColorScheme(
    primary = primaryLight,
    onPrimary = onPrimaryLight,
    primaryContainer = primaryContainerLight,
    onPrimaryContainer = onPrimaryContainerLight,
    secondary = secondaryLight,
    onSecondary = onSecondaryLight,
    secondaryContainer = secondaryContainerLight,
    onSecondaryContainer = onSecondaryContainerLight,
    tertiary = tertiaryLight,
    onTertiary = onTertiaryLight,
    tertiaryContainer = tertiaryContainerLight,
    onTertiaryContainer = onTertiaryContainerLight,
    error = errorLight,
    onError = onErrorLight,
    errorContainer = errorContainerLight,
    onErrorContainer = onErrorContainerLight,
    background = backgroundLight,
    onBackground = onBackgroundLight,
    surface = surfaceLight,
    onSurface = onSurfaceLight,
    surfaceVariant = surfaceVariantLight,
    onSurfaceVariant = onSurfaceVariantLight,
    outline = outlineLight,
    outlineVariant = outlineVariantLight,
    scrim = scrimLight,
    inverseSurface = inverseSurfaceLight,
    inverseOnSurface = inverseOnSurfaceLight,
    inversePrimary = inversePrimaryLight,
    surfaceDim = surfaceDimLight,
    surfaceBright = surfaceBrightLight,
    surfaceContainerLowest = surfaceContainerLowestLight,
    surfaceContainerLow = surfaceContainerLowLight,
    surfaceContainer = surfaceContainerLight,
    surfaceContainerHigh = surfaceContainerHighLight,
    surfaceContainerHighest = surfaceContainerHighestLight,
    primaryFixed = primaryFixedLight,
    primaryFixedDim = primaryFixedDimLight,
    onPrimaryFixed = onPrimaryFixedLight,
    onPrimaryFixedVariant = onPrimaryFixedVariantLight,
    secondaryFixed = secondaryFixedLight,
    secondaryFixedDim = secondaryFixedDimLight,
    onSecondaryFixed = onSecondaryFixedLight,
    onSecondaryFixedVariant = onSecondaryFixedVariantLight,
    tertiaryFixed = tertiaryFixedLight,
    tertiaryFixedDim = tertiaryFixedDimLight,
    onTertiaryFixed = onTertiaryFixedLight,
    onTertiaryFixedVariant = onTertiaryFixedVariantLight,
)

private val darkScheme = darkColorScheme(
    primary = primaryDark,
    onPrimary = onPrimaryDark,
    primaryContainer = primaryContainerDark,
    onPrimaryContainer = onPrimaryContainerDark,
    secondary = secondaryDark,
    onSecondary = onSecondaryDark,
    secondaryContainer = secondaryContainerDark,
    onSecondaryContainer = onSecondaryContainerDark,
    tertiary = tertiaryDark,
    onTertiary = onTertiaryDark,
    tertiaryContainer = tertiaryContainerDark,
    onTertiaryContainer = onTertiaryContainerDark,
    error = errorDark,
    onError = onErrorDark,
    errorContainer = errorContainerDark,
    onErrorContainer = onErrorContainerDark,
    background = backgroundDark,
    onBackground = onBackgroundDark,
    surface = surfaceDark,
    onSurface = onSurfaceDark,
    surfaceVariant = surfaceVariantDark,
    onSurfaceVariant = onSurfaceVariantDark,
    outline = outlineDark,
    outlineVariant = outlineVariantDark,
    scrim = scrimDark,
    inverseSurface = inverseSurfaceDark,
    inverseOnSurface = inverseOnSurfaceDark,
    inversePrimary = inversePrimaryDark,
    surfaceDim = surfaceDimDark,
    surfaceBright = surfaceBrightDark,
    surfaceContainerLowest = surfaceContainerLowestDark,
    surfaceContainerLow = surfaceContainerLowDark,
    surfaceContainer = surfaceContainerDark,
    surfaceContainerHigh = surfaceContainerHighDark,
    surfaceContainerHighest = surfaceContainerHighestDark,
    primaryFixed = primaryFixedDark,
    primaryFixedDim = primaryFixedDimDark,
    onPrimaryFixed = onPrimaryFixedDark,
    onPrimaryFixedVariant = onPrimaryFixedVariantDark,
    secondaryFixed = secondaryFixedDark,
    secondaryFixedDim = secondaryFixedDimDark,
    onSecondaryFixed = onSecondaryFixedDark,
    onSecondaryFixedVariant = onSecondaryFixedVariantDark,
    tertiaryFixed = tertiaryFixedDark,
    tertiaryFixedDim = tertiaryFixedDimDark,
    onTertiaryFixed = onTertiaryFixedDark,
    onTertiaryFixedVariant = onTertiaryFixedVariantDark,
)
