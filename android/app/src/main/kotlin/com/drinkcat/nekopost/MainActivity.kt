package com.drinkcat.nekopost

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.drinkcat.nekopost.core.ui.fold.rememberFoldState
import com.drinkcat.nekopost.core.ui.theme.NekopostTheme
import com.drinkcat.nekopost.ui.shell.NekopostRoot

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
            NekopostTheme {
                NekopostRoot(
                    controller = container.forwarder,
                    fold = rememberFoldState(),
                    appVersion = container.appVersion,
                )
            }
        }
    }
}
