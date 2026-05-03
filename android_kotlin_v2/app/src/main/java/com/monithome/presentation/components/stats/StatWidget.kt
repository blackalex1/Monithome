package com.monithome.presentation.components.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun StatWidget(
    title: String,
    stats: Map<String, Any>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))

            // CPU Load
            StatItem(
                label = "CPU",
                value = "${(stats["cpu"] as? Number)?.toInt() ?: 0}%",
                temp = (stats["cpu_temp"] as? Number)?.toInt()?.let { "$it°C" },
                progress = (stats["cpu"] as? Number)?.toFloat()?.div(100f) ?: 0f,
                color = Color(0xFF38BDF8)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // GPU Load (if exists)
            if (stats["has_gpu"] == true) {
                StatItem(
                    label = "GPU",
                    value = "${(stats["gpu_load"] as? Number)?.toInt() ?: 0}%",
                    temp = (stats["gpu_temp"] as? Number)?.toInt()?.let { "$it°C" },
                    progress = (stats["gpu_load"] as? Number)?.toFloat()?.div(100f) ?: 0f,
                    color = Color(0xFFFBBF24)
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // RAM Load
            StatItem(
                label = "RAM",
                value = "${(stats["ram_used"] as? Number)?.toDouble() ?: 0.0} / ${(stats["ram_total"] as? Number)?.toDouble() ?: 0.0} GB",
                progress = (stats["ram_percent"] as? Number)?.toFloat()?.div(100f) ?: 0f,
                color = Color(0xFFA78BFA)
            )
        }
    }
}

@Composable
fun StatItem(
    label: String,
    value: String,
    temp: String? = null,
    progress: Float,
    color: Color
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, color = Color.Gray, fontSize = 14.sp)
            Row {
                if (temp != null) {
                    Text(text = temp, color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(end = 8.dp))
                }
                Text(text = value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF2C2C2C))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(color)
            )
        }
    }
}
