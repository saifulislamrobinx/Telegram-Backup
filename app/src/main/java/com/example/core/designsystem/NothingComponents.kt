package com.example.core.designsystem

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.common.FormatUtils
import com.example.ui.theme.NothingAmber
import com.example.ui.theme.NothingGreen
import com.example.ui.theme.NothingRed
import com.example.ui.theme.SpaceMonoFontFamily
import java.util.Locale
import kotlin.math.sin

/**
 * Subtle dot-matrix background overlay or header strip inspired by Nothing OS.
 */
@Composable
fun DotMatrixBanner(
    modifier: Modifier = Modifier,
    animatePulse: Boolean = false,
    activeRedDot: Boolean = true
) {
    val transition = rememberInfiniteTransition(label = "dot_matrix_pulse")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 6.283f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )
    val dotColor = MaterialTheme.colorScheme.outlineVariant
    val highlightColor = MaterialTheme.colorScheme.primary

    Canvas(modifier = modifier) {
        val spacing = 10.dp.toPx()
        val radius = 1.35.dp.toPx()
        val cols = (size.width / spacing).toInt().coerceAtLeast(1)
        val rows = (size.height / spacing).toInt().coerceAtLeast(1)

        for (r in 0..rows) {
            for (c in 0..cols) {
                val x = c * spacing + spacing / 2f
                val y = r * spacing + spacing / 2f
                if (x <= size.width && y <= size.height) {
                    val isCornerAccent = activeRedDot && r == 1 && c == cols - 2
                    val wave = if (animatePulse) {
                        ((sin(phase + (c * 0.35f) + (r * 0.5f)) + 1f) / 2f)
                    } else {
                        0.2f
                    }
                    val color = when {
                        isCornerAccent -> NothingRed
                        animatePulse && wave > 0.85f -> highlightColor.copy(alpha = 0.7f)
                        else -> dotColor.copy(alpha = 0.45f)
                    }
                    val currentRadius = if (isCornerAccent) radius * 1.9f else radius
                    drawCircle(
                        color = color,
                        radius = currentRadius,
                        center = Offset(x, y)
                    )
                }
            }
        }
    }
}

/**
 * Dotted horizontal separator for technical sections.
 */
@Composable
fun DottedDivider(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.outline
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(2.dp)
    ) {
        drawLine(
            color = color,
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = 1.5.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()), 0f)
        )
    }
}

/**
 * Restrained rounded-rectangle card with 1dp border and dark graphite surface.
 */
@Composable
fun NothingCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    borderColor: Color = MaterialTheme.colorScheme.outline,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(12.dp)
    val clickModifier = if (onClick != null) {
        Modifier.clickable {
            FormatUtils.triggerSubtleHaptic(context)
            onClick()
        }
    } else {
        Modifier
    }

    Surface(
        modifier = modifier
            .clip(shape)
            .then(clickModifier),
        shape = shape,
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content
        )
    }
}

enum class StatusTone {
    NEUTRAL,
    ACTIVE_RED,
    VERIFIED_GREEN,
    WARNING_AMBER
}

@Composable
fun NothingStatusPill(
    label: String,
    tone: StatusTone = StatusTone.NEUTRAL,
    modifier: Modifier = Modifier
) {
    val dotColor = when (tone) {
        StatusTone.ACTIVE_RED -> NothingRed
        StatusTone.VERIFIED_GREEN -> NothingGreen
        StatusTone.WARNING_AMBER -> NothingAmber
        StatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(6.dp)
            )
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label.uppercase(Locale.US),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun SectionHeader(
    indexCode: String,
    title: String,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "[$indexCode]",
                    style = MaterialTheme.typography.labelMedium,
                    color = NothingRed,
                    fontFamily = SpaceMonoFontFamily
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title.uppercase(Locale.US),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            if (trailing != null) {
                trailing()
            }
        }
        if (!subtitle.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun TelemetryMetricTile(
    label: String,
    value: String,
    subValue: String? = null,
    accentRed: Boolean = false,
    modifier: Modifier = Modifier
) {
    NothingCard(
        modifier = modifier,
        contentPadding = 12.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label.uppercase(Locale.US),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (accentRed) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(NothingRed)
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontFamily = SpaceMonoFontFamily,
                fontSize = 18.sp
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (!subValue.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subValue,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Segmented dot-matrix progress bar inspired by Nothing OS battery / glyph progress widgets.
 */
@Composable
fun DotMatrixProgressBar(
    progress: Float, // 0f..1f
    modifier: Modifier = Modifier,
    segments: Int = 24,
    activeColor: Color = NothingRed
) {
    val clamped = progress.coerceIn(0f, 1f)
    val inactiveColor = MaterialTheme.colorScheme.outline

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp)
    ) {
        val gap = 3.dp.toPx()
        val totalGaps = (segments - 1) * gap
        val segWidth = ((size.width - totalGaps) / segments).coerceAtLeast(2f)
        val activeSegments = (clamped * segments).toInt()

        for (i in 0 until segments) {
            val left = i * (segWidth + gap)
            val color = if (i < activeSegments || (clamped > 0f && i == 0)) {
                activeColor
            } else {
                inactiveColor
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(left, 0f),
                size = androidx.compose.ui.geometry.Size(segWidth, size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx(), 2.dp.toPx())
            )
        }
    }
}

@Composable
fun NothingPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accentRed: Boolean = false,
    testTag: String? = null
) {
    val context = LocalContext.current
    val containerColor = if (accentRed) NothingRed else MaterialTheme.colorScheme.primary
    val contentColor = if (accentRed) Color.White else MaterialTheme.colorScheme.onPrimary

    Button(
        onClick = {
            FormatUtils.triggerSubtleHaptic(context)
            onClick()
        },
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .let { if (testTag != null) it.testTag(testTag) else it }
    ) {
        Text(
            text = text.uppercase(Locale.US),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun NothingOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    testTag: String? = null
) {
    val context = LocalContext.current
    OutlinedButton(
        onClick = {
            FormatUtils.triggerSubtleHaptic(context)
            onClick()
        },
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .let { if (testTag != null) it.testTag(testTag) else it }
    ) {
        Text(
            text = text.uppercase(Locale.US),
            style = MaterialTheme.typography.labelLarge
        )
    }
}
