package com.example.appdev.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.appdev.R
import com.example.appdev.ui.theme.HeatColors

/**
 * How a screen opens the AI assistant. Provided once by the navigation host, so any screen header can
 * show the assistant icon without being passed navigation code.
 */
val LocalOpenAssistant = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * Opens the assistant and asks it a ready-made question (for example "explain this incident"). The
 * question appears in the chat as the user's own message, so it is always visible and can be edited out
 * of future questions by clearing the chat.
 */
val LocalAskAssistant = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * The small assistant icon shown in a screen's top bar. It looks compact (36 dp) but keeps the
 * standard 48 dp touch target, and always opens the assistant for the screen it sits on.
 */
@Composable
fun AssistantIconButton(modifier: Modifier = Modifier) {
    val open = LocalOpenAssistant.current
    IconButton(
        onClick = open,
        modifier = modifier
            .size(36.dp)
            .background(HeatColors.SurfaceRaised, CircleShape)
            .border(BorderStroke(1.dp, HeatColors.OutlineSubtle), CircleShape),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_sparkle),
            contentDescription = stringResource(R.string.ai_fab),
            tint = HeatColors.Accent,
            modifier = Modifier.size(18.dp),
        )
    }
}
