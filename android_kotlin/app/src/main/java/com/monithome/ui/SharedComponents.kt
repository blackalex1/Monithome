package com.monithome.ui

import androidx.core.graphics.toColorInt
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import com.monithome.models.ColorRange

@Composable
fun ValueBlock(
    label: String,
    value: String,
    unit: String = "",
    icon: ImageVector? = null,
    secondaryValue: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(MonitTheme.Primary.copy(alpha = 0.1f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MonitTheme.Primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column {
                Text(label, color = MonitTheme.TextSecondary, fontSize = 13.sp)
                if (secondaryValue != null) {
                    Text(secondaryValue, color = MonitTheme.TextSecondary.copy(alpha = 0.6f), fontSize = 11.sp)
                }
            }
        }
        
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            if (unit.isNotEmpty()) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    unit,
                    color = MonitTheme.TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
    }
}

@Composable
fun AnimatedProgressBar(
    value: Float,
    label: String? = null,
    colorRanges: List<ColorRange>? = null
) {
    val animatedProgress by animateFloatAsState(
        targetValue = value / 100f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "progress"
    )

    val barColor = remember(value, colorRanges) {
        val range = colorRanges?.find { value >= (it.min ?: 0f) && value <= (it.max ?: 100f) }
        if (range != null && range.color != null) {
            try { Color(range.color.toColorInt()) } catch (e: Exception) { MonitTheme.Primary }
        } else {
            MonitTheme.Primary
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(12.dp),
            contentAlignment = Alignment.Center
        ) {
            val neonGlowColors = remember(barColor) { listOf(barColor.copy(alpha = 0.3f), Color.Transparent) }
            val barGradientColors = remember(barColor) { listOf(barColor.copy(alpha = 0.6f), barColor) }

            Canvas(modifier = Modifier.fillMaxWidth().height(6.dp)) {
                val width = size.width
                val height = size.height
                val corner = height / 2f
                
                // 1. Track
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.06f),
                    size = size,
                    cornerRadius = CornerRadius(corner, corner)
                )
                
                if (animatedProgress > 0) {
                    val progressWidth = width * animatedProgress
                    val glowRadius = 14.dp.toPx()
                    val highlightRadius = 2.dp.toPx()
                    
                    // 2. Neon Glow (Outer)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = neonGlowColors,
                            center = Offset(progressWidth, corner),
                            radius = glowRadius
                        ),
                        radius = glowRadius,
                        center = Offset(progressWidth, corner)
                    )
                    
                    // 3. Main Progress Bar
                    drawRoundRect(
                        brush = Brush.horizontalGradient(colors = barGradientColors),
                        size = Size(progressWidth, height),
                        cornerRadius = CornerRadius(corner, corner)
                    )
                    
                    // 4. Highlight Tip
                    drawCircle(
                        color = Color.White.copy(alpha = 0.8f),
                        radius = highlightRadius,
                        center = Offset(progressWidth, corner)
                    )
                }
            }
        }

        if (label != null) {
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                label,
                color = MonitTheme.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(42.dp)
            )
        }
    }
}

@Composable
fun WidgetContainer(
    content: @Composable ColumnScope.() -> Unit
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        cornerRadius = 24.dp
    ) {
        content()
    }
}

fun mapIcon(iconName: String?): ImageVector {
    return when(iconName?.lowercase()) {
        "moon", "sleep" -> Icons.Default.Bedtime
        "power", "shutdown" -> Icons.Default.PowerSettingsNew
        "lock" -> Icons.Default.Lock
        "refresh", "restart", "refreshcw" -> Icons.Default.Refresh
        "volume" -> Icons.AutoMirrored.Filled.VolumeUp
        "cpu" -> Icons.Default.Memory
        "gpu" -> Icons.Default.DeveloperBoard
        "ram" -> Icons.Default.Dns
        else -> Icons.AutoMirrored.Filled.Help // Изменил на знак вопроса для неизвестных, чтобы было понятнее
    }
}
