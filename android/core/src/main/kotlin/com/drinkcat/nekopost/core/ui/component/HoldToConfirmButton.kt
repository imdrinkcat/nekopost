package com.drinkcat.nekopost.core.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Danger confirmation (design §10.9): the action only fires after the button
 * has been held for [holdMillis]. A fill sweeps across while holding; letting
 * go early rewinds it. Accessibility services get the same action as a
 * long-click with an explicit label.
 */
@Composable
fun HoldToConfirmButton(
    text: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    holdMillis: Int = 1500,
    container: Color = MaterialTheme.colorScheme.errorContainer,
    fill: Color = MaterialTheme.colorScheme.error.copy(alpha = 0.32f),
    content: Color = MaterialTheme.colorScheme.onErrorContainer,
) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val confirm by rememberUpdatedState(onConfirm)
    val isEnabled by rememberUpdatedState(enabled)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(CircleShape)
            .background(if (enabled) container else container.copy(alpha = 0.4f))
            .drawBehind {
                val w = size.width * progress.value
                val x = if (layoutDirection == LayoutDirection.Rtl) size.width - w else 0f
                drawRect(fill, topLeft = androidx.compose.ui.geometry.Offset(x, 0f), size = Size(w, size.height))
            }
            .semantics {
                role = Role.Button
                if (enabled) {
                    onLongClick(label = text) {
                        confirm()
                        true
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        if (!isEnabled) return@detectTapGestures
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        var done = false
                        val job: Job = scope.launch {
                            val remaining = ((1f - progress.value) * holdMillis).toInt().coerceAtLeast(1)
                            progress.animateTo(1f, tween(remaining, easing = LinearEasing))
                            done = true
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            confirm()
                            progress.snapTo(0f)
                        }
                        tryAwaitRelease()
                        if (!done) {
                            job.cancel()
                            scope.launch { progress.animateTo(0f, tween(220)) }
                        }
                    },
                )
            }
            .padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = content,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
        )
    }
}
