package com.drinkcat.nekopost.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.drinkcat.nekopost.core.ui.token.NekoTokens

internal val NekoLightColorScheme: ColorScheme = with(NekoTokens.Light) {
    lightColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = inversePrimary,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = surface,
        onBackground = onSurface,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceTint = primary,
        inverseSurface = inverseSurface,
        inverseOnSurface = inverseOnSurface,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        outline = outline,
        outlineVariant = outlineVariant,
        scrim = scrim,
        surfaceBright = surfaceBright,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainerLowest = surfaceContainerLowest,
        surfaceDim = surfaceDim,
    )
}

internal val NekoDarkColorScheme: ColorScheme = with(NekoTokens.Dark) {
    darkColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = inversePrimary,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = surface,
        onBackground = onSurface,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceTint = primary,
        inverseSurface = inverseSurface,
        inverseOnSurface = inverseOnSurface,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        outline = outline,
        outlineVariant = outlineVariant,
        scrim = scrim,
        surfaceBright = surfaceBright,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainerLowest = surfaceContainerLowest,
        surfaceDim = surfaceDim,
    )
}

/** Foreground + container pair for one status family. */
@Immutable
data class ToneColors(val content: Color, val container: Color)

/** Semantic tones used by status pills and cards. */
enum class StatusTone { Success, Pending, Warning, Uncertain, Error, Neutral, Brand }

/** Colours outside the Material scheme: fixed status families (design §9.3). */
@Immutable
data class NekoExtendedColors(
    val success: ToneColors,
    val pending: ToneColors,
    val warning: ToneColors,
    val uncertain: ToneColors,
    val error: ToneColors,
    val neutral: ToneColors,
    val brand: ToneColors,
) {
    fun tone(tone: StatusTone): ToneColors = when (tone) {
        StatusTone.Success -> success
        StatusTone.Pending -> pending
        StatusTone.Warning -> warning
        StatusTone.Uncertain -> uncertain
        StatusTone.Error -> error
        StatusTone.Neutral -> neutral
        StatusTone.Brand -> brand
    }

    companion object {
        val Light = NekoExtendedColors(
            success = ToneColors(NekoTokens.Status.deliveredLight, NekoTokens.Status.deliveredContainerLight),
            pending = ToneColors(NekoTokens.Light.secondary, NekoTokens.Light.secondaryContainer),
            warning = ToneColors(NekoTokens.Status.retryingLight, NekoTokens.Status.retryingContainerLight),
            uncertain = ToneColors(NekoTokens.Status.uncertainLight, NekoTokens.Status.uncertainContainerLight),
            error = ToneColors(NekoTokens.Light.error, NekoTokens.Light.errorContainer),
            neutral = ToneColors(NekoTokens.Light.onSurfaceVariant, NekoTokens.Light.surfaceContainerHighest),
            brand = ToneColors(NekoTokens.Light.onPrimaryContainer, NekoTokens.Light.primaryContainer),
        )

        val Dark = NekoExtendedColors(
            success = ToneColors(NekoTokens.Status.deliveredDark, NekoTokens.Status.deliveredContainerDark),
            pending = ToneColors(NekoTokens.Dark.secondary, NekoTokens.Dark.secondaryContainer),
            warning = ToneColors(NekoTokens.Status.retryingDark, NekoTokens.Status.retryingContainerDark),
            uncertain = ToneColors(NekoTokens.Status.uncertainDark, NekoTokens.Status.uncertainContainerDark),
            error = ToneColors(NekoTokens.Dark.error, NekoTokens.Dark.errorContainer),
            neutral = ToneColors(NekoTokens.Dark.onSurfaceVariant, NekoTokens.Dark.surfaceContainerHighest),
            brand = ToneColors(NekoTokens.Dark.onPrimaryContainer, NekoTokens.Dark.primaryContainer),
        )
    }
}

internal val LocalNekoColors = staticCompositionLocalOf { NekoExtendedColors.Light }
