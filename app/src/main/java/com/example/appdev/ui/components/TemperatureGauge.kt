package com.example.appdev.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.appdev.ui.theme.HeatColors
import kotlin.math.cos
import kotlin.math.sin

private const val START_ANGLE = 135f
private const val SWEEP_ANGLE = 270f
private const val MINOR_TICKS = 27

/**
 * A 270° arc gauge. The value arc animates between readings and carries a soft glow plus a bright
 * end marker; a halo behind it takes the status colour, and fine tick marks show the scale. Longer
 * orange and red ticks show where the early-warning band and the threshold begin.
 */
@Composable
fun TemperatureGauge(
    temperatureC: Float?,
    thresholdC: Float,
    warningMarginC: Float,
    color: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val targetFraction = temperatureC?.let { GaugeScale.fraction(it, thresholdC) } ?: 0f
    val fraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "gaugeFraction",
    )
    val arcColor by animateColorAsState(color, tween(600), label = "gaugeColor")
    val thresholdFraction = GaugeScale.fraction(thresholdC, thresholdC)
    val warningFraction = GaugeScale.fraction(thresholdC - warningMarginC, thresholdC)

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 14.dp.toPx()
            val inset = stroke / 2f + 14.dp.toPx()
            val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
            val topLeft = Offset(inset, inset)
            val radius = arcSize.width / 2f

            // Status-coloured halo behind the gauge.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(arcColor.copy(alpha = 0.16f), Color.Transparent),
                    center = center,
                    radius = radius * 1.05f,
                ),
                radius = radius * 1.05f,
                center = center,
            )

            // Track.
            drawArc(
                color = HeatColors.SurfaceHighest,
                startAngle = START_ANGLE, sweepAngle = SWEEP_ANGLE, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )

            // Fine scale marks just inside the track.
            for (i in 0..MINOR_TICKS) {
                val f = i / MINOR_TICKS.toFloat()
                drawRadialMark(
                    fraction = f,
                    innerRadius = radius - stroke / 2f - 12.dp.toPx(),
                    outerRadius = radius - stroke / 2f - 7.dp.toPx(),
                    color = HeatColors.Outline.copy(alpha = if (i % 3 == 0) 0.9f else 0.45f),
                    width = 1.5.dp.toPx(),
                )
            }

            if (temperatureC != null && fraction > 0f) {
                // Glow under the value arc, then the arc itself.
                drawArc(
                    color = arcColor.copy(alpha = 0.20f),
                    startAngle = START_ANGLE, sweepAngle = SWEEP_ANGLE * fraction, useCenter = false,
                    topLeft = topLeft, size = arcSize,
                    style = Stroke(width = stroke * 2.2f, cap = StrokeCap.Round),
                )
                drawArc(
                    color = arcColor,
                    startAngle = START_ANGLE, sweepAngle = SWEEP_ANGLE * fraction, useCenter = false,
                    topLeft = topLeft, size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                // Bright marker at the current value.
                val end = pointOnArc(START_ANGLE + SWEEP_ANGLE * fraction, radius)
                drawCircle(arcColor.copy(alpha = 0.25f), radius = 13.dp.toPx(), center = end)
                drawCircle(Color.White.copy(alpha = 0.92f), radius = 5.dp.toPx(), center = end)
            }

            // Where the warning band and the threshold begin.
            drawRadialMark(warningFraction, radius + stroke / 2f + 3.dp.toPx(), radius + stroke / 2f + 11.dp.toPx(), HeatColors.Elevated, 3.dp.toPx())
            drawRadialMark(thresholdFraction, radius + stroke / 2f + 3.dp.toPx(), radius + stroke / 2f + 11.dp.toPx(), HeatColors.Critical, 3.dp.toPx())
        }
        content()
    }
}

private fun DrawScope.pointOnArc(angleDegrees: Float, radius: Float): Offset {
    val a = Math.toRadians(angleDegrees.toDouble())
    return Offset(center.x + radius * cos(a).toFloat(), center.y + radius * sin(a).toFloat())
}

private fun DrawScope.drawRadialMark(fraction: Float, innerRadius: Float, outerRadius: Float, color: Color, width: Float) {
    val angle = START_ANGLE + SWEEP_ANGLE * fraction
    drawLine(color, pointOnArc(angle, innerRadius), pointOnArc(angle, outerRadius), strokeWidth = width, cap = StrokeCap.Round)
}
