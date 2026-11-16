package com.drinkcat.nekopost.inbox.notify

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.drinkcat.nekopost.MainActivity
import com.drinkcat.nekopost.NekopostApp
import com.drinkcat.nekopost.inbox.messagePattern
import com.drinkcat.nekopost.ui.shell.NavRequest

/**
 * Notification tap target (non-exported, translucent, no history). It validates
 * the message ID, hands it to the running app in-process and brings
 * MainActivity to the front; MainActivity itself still ignores every Intent.
 * The message opens once the app lock is open.
 */
class OpenMessageActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as? NekopostApp
        route(intent?.getStringExtra(EXTRA_MESSAGE_ID)) { app?.container?.navigation?.value = it }
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }

    companion object {
        const val EXTRA_MESSAGE_ID = "com.drinkcat.nekopost.MESSAGE_ID"

        /** Accepts only a well-formed message ID; anything else just opens the app. */
        fun route(raw: String?, sink: (NavRequest) -> Unit): Boolean {
            val id = raw?.takeIf(messagePattern::matches) ?: return false
            sink(NavRequest.OpenMessage(id))
            return true
        }
    }
}
