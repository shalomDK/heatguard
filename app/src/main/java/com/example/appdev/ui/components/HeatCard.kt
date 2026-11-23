package com.example.appdev.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.appdev.ui.theme.HeatColors

val CardShape = RoundedCornerShape(22.dp)

/** Subtle top-lit gradient used for ordinary cards: a hint of depth without any glare. */
private val CardBrush = Brush.verticalGradient(listOf(HeatColors.SurfaceLit, HeatColors.Surface))

/** A soft edge that is slightly brighter at the top, like light catching the rim of the card. */
private val CardBorderBrush = Brush.verticalGradient(listOf(HeatColors.Outline.copy(alpha = 0.85f), HeatColors.OutlineSubtle))

/**
 * Standard elevated surface used across the app. Clickable only when [onClick] is provided.
 * Cards with the default colour get the gradient look; tinted cards (alerts, readiness) keep their
 * flat status tint so the colour still carries the meaning.
 */
@Composable
fun HeatCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = HeatColors.Surface,
    border: Color = HeatColors.OutlineSubtle,
    contentPadding: Dp = 18.dp,
    spacing: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val default = color == HeatColors.Surface && border == HeatColors.OutlineSubtle
    val cardModifier = if (default) modifier.background(CardBrush, CardShape) else modifier
    val surfaceColor = if (default) Color.Transparent else color
    val borderStroke = if (default) BorderStroke(1.dp, CardBorderBrush) else BorderStroke(1.dp, border)

    val inner: @Composable () -> Unit = {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = cardModifier,
            shape = CardShape,
            color = surfaceColor,
            border = borderStroke,
            content = inner,
        )
    } else {
        Surface(
            modifier = cardModifier,
            shape = CardShape,
            color = surfaceColor,
            border = borderStroke,
            content = inner,
        )
    }
}
