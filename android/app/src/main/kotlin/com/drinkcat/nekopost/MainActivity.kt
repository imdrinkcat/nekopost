package com.drinkcat.nekopost

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import com.drinkcat.nekopost.core.ui.fold.rememberFoldState
import com.drinkcat.nekopost.core.ui.motion.LocalReducedMotion
import com.drinkcat.nekopost.core.ui.motion.isReducedMotionEnabled
import com.drinkcat.nekopost.core.ui.theme.NekopostTheme
import com.drinkcat.nekopost.system.LocalAppIconLoader
import com.drinkcat.nekopost.ui.NekopostRoot

/**
 * The single activity. It handles fold/unfold/rotation itself (configChanges in
 * the manifest), so Compose recomposes in place and nothing is lost. It never
 * reads its Intent: extras and data are ignored, it always opens the home screen.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as NekopostApp).container
        setContent {
            // Re-read on configuration changes (the accessibility switch arrives as one).
            val configuration = LocalConfiguration.current
            val reducedMotion = remember(configuration) { isReducedMotionEnabled() }
            CompositionLocalProvider(
                LocalReducedMotion provides reducedMotion,
                LocalAppIconLoader provides container.iconLoader,
            ) {
                NekopostTheme {
                    NekopostRoot(container = container, fold = rememberFoldState())
                }
            }
        }
    }
}
