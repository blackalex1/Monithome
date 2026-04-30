package com.monithome.ui

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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Column {
                Text(label, color = Color(0xFF94A3B8), fontSize = 14.sp)
                if (secondaryValue != null) {
                    Text(secondaryValue, color = Color(0xFF475569), fontSize = 12.sp)
                }
            }
        }
        
        Text(
            text = "$value$unit",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium
        )
    }
}

@Composable
fun AnimatedProgressBar(
    value: Float,
    colorRanges: List<ColorRange>? = null
) {
    val animatedProgress by animateFloatAsState(
        targetValue = value / 100f,
        label = "progress"
    )

    val barColor = remember(value, colorRanges) {
        val range = colorRanges?.find { value >= (it.min ?: 0f) && value <= (it.max ?: 100f) }
        if (range != null) {
            Color(android.graphics.Color.parseColor(range.color))
        } else {
            Color(0xFF38BDF8)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color.White.copy(alpha = 0.05f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(animatedProgress)
                .background(barColor)
        )
    }
}

@Composable
fun WidgetContainer(
    isSmall: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1E293B).copy(alpha = 0.4f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(if (isSmall) 8.dp else 12.dp)) {
            content()
        }
    }
}

fun mapIcon(iconName: String?): ImageVector {
    return when(iconName?.lowercase()) {
        "moon", "sleep" -> Icons.Default.Bedtime
        "power", "shutdown" -> Icons.Default.PowerSettingsNew
        "lock" -> Icons.Default.Lock
        "refresh", "restart" -> Icons.Default.Refresh
        "volume" -> Icons.Default.VolumeUp
        else -> Icons.Default.Settings
    }
}
