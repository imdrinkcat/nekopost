package com.drinkcat.nekopost.ui.shell

/**
 * A navigation request from outside the composition (a notification tap via
 * OpenMessageActivity). The root consumes it after the app lock is open.
 */
sealed interface NavRequest {
    data class OpenMessage(val id: String) : NavRequest
}
