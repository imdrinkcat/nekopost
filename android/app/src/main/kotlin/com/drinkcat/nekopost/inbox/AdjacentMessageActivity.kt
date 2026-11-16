package com.drinkcat.nekopost.inbox

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.drinkcat.nekopost.NekopostApp
import com.drinkcat.nekopost.core.ui.fold.rememberFoldState
import com.drinkcat.nekopost.core.ui.theme.NekopostTheme
import com.drinkcat.nekopost.lock.AppLockHost
import com.drinkcat.nekopost.system.LocalExternalLauncher
import com.drinkcat.nekopost.ui.shell.InboxMemory
import com.drinkcat.nekopost.ui.shell.PaneMode

/**
 * 在旁边打开 (§10.4 ⑪): one message in a second window next to the app. It
 * shares the process-wide app lock (one unlock opens both windows) and is
 * just as secure: FLAG_SECURE, no Recents thumbnail.
 */
class AdjacentMessageActivity : ComponentActivity() {
    private val container get() = (application as NekopostApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        val id = intent.getStringExtra(EXTRA_MESSAGE_ID)?.takeIf(messagePattern::matches)
        if (id == null) {
            finish()
            return
        }
        val container = container
        val controller = container.newInboxWindow()
        setContent {
            val fold = rememberFoldState()
            val memory = remember { InboxMemory(selectedId = id) }
            CompositionLocalProvider(LocalExternalLauncher provides container.launcher) {
                NekopostTheme {
                    AppLockHost(container.lock, fold) {
                        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
                            Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                                InboxRoute(
                                    inbox = controller,
                                    fold = fold,
                                    mode = PaneMode.Single,
                                    memory = memory,
                                    appScope = container.appScope,
                                    envelopeEnabled = false,
                                    serverHint = null,
                                    onOpenSettings = ::finish,
                                    adjacent = true,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        container.lock.activityResumed()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        container.lock.touch()
    }
}

private const val EXTRA_MESSAGE_ID = "com.drinkcat.nekopost.MESSAGE_ID"

internal fun openAdjacent(context: Context, id: String) {
    require(messagePattern.matches(id))
    context.startActivity(
        Intent(context, AdjacentMessageActivity::class.java)
            .putExtra(EXTRA_MESSAGE_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
    )
}
