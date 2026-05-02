package com.monithome.ui

import androidx.core.graphics.toColorInt
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
            try { Color(range.color!!.toColorInt()) } catch (e: Exception) { MonitTheme.Primary }
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
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White.copy(alpha = 0.05f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animatedProgress)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(barColor.copy(alpha = 0.7f), barColor)
                        )
                    )
            )
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
        "volume" -> Icons.Default.VolumeUp
        "cpu" -> Icons.Default.Memory
        "gpu" -> Icons.Default.DeveloperBoard
        "ram" -> Icons.Default.Dns
        else -> Icons.Default.Help // Изменил на знак вопроса для неизвестных, чтобы было понятнее
    }
}
