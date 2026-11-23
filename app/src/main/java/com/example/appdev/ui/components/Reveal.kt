package com.example.appdev.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay

private const val STAGGER_MILLIS = 55L
private const val MAX_STAGGER_STEPS = 8
private const val RISE_DP = 20f

/**
 * Fades its content in while sliding it up a few pixels. Give successive cards increasing [index]
 * values and they appear one after another. It plays once when the screen first shows, and the
 * delay is capped so a long list never makes the user wait.
 */
@Composable
fun Reveal(index: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index.coerceIn(0, MAX_STAGGER_STEPS) * STAGGER_MILLIS)
        progress.animateTo(1f, tween(durationMillis = 380, easing = FastOutSlowInEasing))
    }
    Box(
        modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * RISE_DP * density
        },
    ) {
        content()
    }
}
