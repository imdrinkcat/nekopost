package com.drinkcat.nekopost.core.ui.token

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Design tokens of "Nekopost 猫邮局" (design §9).
 *
 * Hand-maintained for now. Once design/tokens.json and `go run ./tools/tokens`
 * exist (web panel milestone) this file becomes generated output; keep the
 * shape (objects and names) stable so the generator can take it over.
 */
object NekoTokens {

    /** Brand seed colours the tonal palettes were derived from (HCT, TonalSpot). */
    object Seed {
        /** 橘猫橙 */
        val Primary = Color(0xFFE07A2E)
        /** 邮政墨蓝 */
        val Secondary = Color(0xFF2F4A6B)
        /** 猫薄荷绿 */
        val Tertiary = Color(0xFF3E8E6A)
        /** 信纸 */
        val Neutral = Color(0xFF8A7B72)
    }

    /** Light scheme: warm "letter paper" surfaces. */
    object Light {
        val primary = Color(0xFF9C4511)
        val onPrimary = Color(0xFFFFFFFF)
        val primaryContainer = Color(0xFFFFDBC8)
        val onPrimaryContainer = Color(0xFF341000)
        val inversePrimary = Color(0xFFFFB68B)
        val secondary = Color(0xFF3A5F8C)
        val onSecondary = Color(0xFFFFFFFF)
        val secondaryContainer = Color(0xFFD3E4FF)
        val onSecondaryContainer = Color(0xFF001C38)
        val tertiary = Color(0xFF23694C)
        val onTertiary = Color(0xFFFFFFFF)
        val tertiaryContainer = Color(0xFFA9F2C9)
        val onTertiaryContainer = Color(0xFF002114)
        val error = Color(0xFFBA1A1A)
        val onError = Color(0xFFFFFFFF)
        val errorContainer = Color(0xFFFFDAD6)
        val onErrorContainer = Color(0xFF410002)
        val surface = Color(0xFFFFF8F5)
        val onSurface = Color(0xFF221A15)
        val surfaceVariant = Color(0xFFF4DED4)
        val onSurfaceVariant = Color(0xFF52443C)
        val surfaceDim = Color(0xFFE8D7CE)
        val surfaceBright = Color(0xFFFFF8F5)
        val surfaceContainerLowest = Color(0xFFFFFFFF)
        val surfaceContainerLow = Color(0xFFFCF1EB)
        val surfaceContainer = Color(0xFFF6ECE6)
        val surfaceContainerHigh = Color(0xFFF0E6E0)
        val surfaceContainerHighest = Color(0xFFEAE0DA)
        val inverseSurface = Color(0xFF382E29)
        val inverseOnSurface = Color(0xFFFFEDE5)
        val outline = Color(0xFF85736A)
        val outlineVariant = Color(0xFFD7C2B8)
        val scrim = Color(0xFF000000)
    }

    /** Dark scheme: the "night-shift post office". */
    object Dark {
        val primary = Color(0xFFFFB68B)
        val onPrimary = Color(0xFF552000)
        val primaryContainer = Color(0xFF783200)
        val onPrimaryContainer = Color(0xFFFFDBC8)
        val inversePrimary = Color(0xFF9C4511)
        val secondary = Color(0xFFA3C9FF)
        val onSecondary = Color(0xFF00315B)
        val secondaryContainer = Color(0xFF20476F)
        val onSecondaryContainer = Color(0xFFD3E4FF)
        val tertiary = Color(0xFF8DD5AE)
        val onTertiary = Color(0xFF003823)
        val tertiaryContainer = Color(0xFF005236)
        val onTertiaryContainer = Color(0xFFA9F2C9)
        val error = Color(0xFFFFB4AB)
        val onError = Color(0xFF690005)
        val errorContainer = Color(0xFF93000A)
        val onErrorContainer = Color(0xFFFFDAD6)
        val surface = Color(0xFF17120F)
        val onSurface = Color(0xFFEFE0D8)
        val surfaceVariant = Color(0xFF52443C)
        val onSurfaceVariant = Color(0xFFD7C2B8)
        val surfaceDim = Color(0xFF17120F)
        val surfaceBright = Color(0xFF3F3631)
        val surfaceContainerLowest = Color(0xFF120D0A)
        val surfaceContainerLow = Color(0xFF1F1915)
        val surfaceContainer = Color(0xFF241D19)
        val surfaceContainerHigh = Color(0xFF2F2723)
        val surfaceContainerHighest = Color(0xFF3A322D)
        val inverseSurface = Color(0xFFEFE0D8)
        val inverseOnSurface = Color(0xFF382E29)
        val outline = Color(0xFFA08D83)
        val outlineVariant = Color(0xFF52443C)
        val scrim = Color(0xFF000000)
    }

    /**
     * Fixed status colours (design §9.3). They never follow dynamic colour, and
     * every status is always shown as icon + text + colour.
     */
    object Status {
        // 猫薄荷绿: delivered / accepted
        val deliveredLight = Color(0xFF1E6E43)
        val deliveredContainerLight = Color(0xFFB6F1CB)
        val deliveredDark = Color(0xFF7FD9A3)
        val deliveredContainerDark = Color(0xFF00522F)

        // 琥珀: retrying / partial / paused
        val retryingLight = Color(0xFF7A5900)
        val retryingContainerLight = Color(0xFFFFDF9E)
        val retryingDark = Color(0xFFF5BF48)
        val retryingContainerDark = Color(0xFF5C4200)

        // 薰衣草: uncertain
        val uncertainLight = Color(0xFF6B4EA2)
        val uncertainContainerLight = Color(0xFFEBDDFF)
        val uncertainDark = Color(0xFFD3BBFF)
        val uncertainContainerDark = Color(0xFF523787)
    }

    /** 4dp spacing grid. */
    object Spacing {
        val xxs = 2.dp
        val xs = 4.dp
        val s = 8.dp
        val m = 12.dp
        val l = 16.dp
        val xl = 24.dp
        val xxl = 32.dp
        val xxxl = 48.dp
    }

    object Radius {
        val chip = 8.dp
        val listItem = 12.dp
        val card = 16.dp
        val largeCard = 24.dp
        val sheet = 28.dp
    }

    object Motion {
        /** Fade used everywhere when the system asks for reduced motion. */
        const val REDUCED_MS = 100
        /** New-message "mail slot" slide-in. */
        const val SLOT_IN_MS = 350
        /** Fold posture changes are debounced before the layout reacts. */
        const val POSTURE_DEBOUNCE_MS = 150L
    }

    object Touch {
        val minTarget = 48.dp
        /** Copy-code button on the cover display. */
        val coverPrimaryTarget = 64.dp
        /** Big buttons on the lower half in tabletop posture. */
        val tabletopTarget = 72.dp
    }

    object Hinge {
        /** Nothing clickable within this distance of a separating hinge. */
        val keepOut = 24.dp
    }

    object Breakpoint {
        /** Content width from which list + detail are shown side by side. */
        val twoPane = 600.dp
    }
}
