package com.drinkcat.nekopost.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import com.drinkcat.nekopost.core.ui.token.NekoTokens

internal val NekoShapes = Shapes(
    extraSmall = RoundedCornerShape(NekoTokens.Radius.chip),
    small = RoundedCornerShape(NekoTokens.Radius.listItem),
    medium = RoundedCornerShape(NekoTokens.Radius.card),
    large = RoundedCornerShape(NekoTokens.Radius.largeCard),
    extraLarge = RoundedCornerShape(NekoTokens.Radius.sheet),
)

/**
 * The Nekopost theme: brand colours by default (one brand on web and app),
 * light/dark following the system. With [dynamicColor] on Android 12+ only the
 * accent roles and surfaces come from the wallpaper; status colours never change.
 */
@Composable
fun NekopostTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val brand = if (darkTheme) NekoDarkColorScheme else NekoLightColorScheme
    val colorScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        val dynamic = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        brand.withAccentsFrom(dynamic)
    } else {
        brand
    }
    val extended = if (darkTheme) NekoExtendedColors.Dark else NekoExtendedColors.Light
    CompositionLocalProvider(LocalNekoColors provides extended) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = NekoTypography,
            shapes = NekoShapes,
            content = content,
        )
    }
}

/** Accessors for theme values outside [MaterialTheme]. */
object NekopostTheme {
    val colors: NekoExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalNekoColors.current
}

private fun ColorScheme.withAccentsFrom(d: ColorScheme): ColorScheme = copy(
    primary = d.primary,
    onPrimary = d.onPrimary,
    primaryContainer = d.primaryContainer,
    onPrimaryContainer = d.onPrimaryContainer,
    inversePrimary = d.inversePrimary,
    secondary = d.secondary,
    onSecondary = d.onSecondary,
    secondaryContainer = d.secondaryContainer,
    onSecondaryContainer = d.onSecondaryContainer,
    tertiary = d.tertiary,
    onTertiary = d.onTertiary,
    tertiaryContainer = d.tertiaryContainer,
    onTertiaryContainer = d.onTertiaryContainer,
    surface = d.surface,
    onSurface = d.onSurface,
    surfaceTint = d.surfaceTint,
    background = d.background,
    onBackground = d.onBackground,
)
