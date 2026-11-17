package com.drinkcat.nekopost.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/*
 * CJK-first typography (design §9.4): the system font for all text, so One UI
 * user fonts are respected; no letter spacing and no italics for Chinese,
 * emphasis only through weight; generous line height for paragraphs.
 *
 * Codes, IDs and statistics use a monospace face with tabular figures. A
 * JetBrains Mono subset will replace FontFamily.Monospace in a later milestone.
 */

private val Base = Typography()

private fun TextStyle.cjk(size: Int, line: Int, weight: FontWeight) = copy(
    fontFamily = FontFamily.Default,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = 0.sp,
)

internal val NekoTypography = Typography(
    displayLarge = Base.displayLarge.cjk(57, 64, FontWeight.Normal),
    displayMedium = Base.displayMedium.cjk(45, 52, FontWeight.Normal),
    // The big verification-code style: monospace, spaced digits.
    displaySmall = Base.displaySmall.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.12.em,
        fontFeatureSettings = "tnum",
    ),
    headlineLarge = Base.headlineLarge.cjk(32, 40, FontWeight.Medium),
    headlineMedium = Base.headlineMedium.cjk(28, 36, FontWeight.Medium),
    headlineSmall = Base.headlineSmall.cjk(24, 32, FontWeight.Medium),
    titleLarge = Base.titleLarge.cjk(22, 28, FontWeight.SemiBold),
    titleMedium = Base.titleMedium.cjk(16, 24, FontWeight.SemiBold),
    titleSmall = Base.titleSmall.cjk(14, 20, FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge.cjk(16, 26, FontWeight.Normal),
    bodyMedium = Base.bodyMedium.cjk(14, 22, FontWeight.Normal),
    bodySmall = Base.bodySmall.cjk(12, 18, FontWeight.Normal),
    labelLarge = Base.labelLarge.cjk(14, 20, FontWeight.Medium),
    labelMedium = Base.labelMedium.cjk(12, 16, FontWeight.Medium),
    labelSmall = Base.labelSmall.cjk(11, 16, FontWeight.Medium),
)

/** Extra text styles that have no Material role. */
object NekoTextStyles {
    /** IDs, hosts, counters: monospace with tabular figures. */
    val mono = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
        fontFeatureSettings = "tnum",
    )

    /** The verification code (§9.4): 36/44 Mono 500, +0.12em, shown in groups of three. */
    val otp = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.12.em,
        fontFeatureSettings = "tnum",
    )

    /** Large statistic numbers (queue size, counts). */
    val statNumber = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 40.sp,
        lineHeight = 44.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
    )
}
