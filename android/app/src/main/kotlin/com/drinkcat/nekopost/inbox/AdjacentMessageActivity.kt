package com.drinkcat.nekopost.inbox

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import com.drinkcat.nekopost.NekopostApp
import com.drinkcat.nekopost.core.ui.fold.rememberFoldState
import com.drinkcat.nekopost.core.ui.theme.NekopostTheme
import com.drinkcat.nekopost.ui.shell.PaneMode

class AdjacentMessageActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        val id = intent.getStringExtra(EXTRA_MESSAGE_ID)?.takeIf(messagePattern::matches)
        if (id == null) { finish(); return }
        val controller = (application as NekopostApp).container.newInboxWindow()
        setContent {
            NekopostTheme {
                Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                    InboxScreen(controller, rememberFoldState(), PaneMode.Single, initialMessage = id)
                }
            }
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        InboxIdle.touch()
    }
}

private const val EXTRA_MESSAGE_ID = "com.drinkcat.nekopost.MESSAGE_ID"

internal fun openAdjacent(context: Context, id: String) {
    require(messagePattern.matches(id))
    context.startActivity(Intent(context, AdjacentMessageActivity::class.java)
        .putExtra(EXTRA_MESSAGE_ID, id)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
}
