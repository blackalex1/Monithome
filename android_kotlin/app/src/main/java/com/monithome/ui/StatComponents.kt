package com.monithome.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monithome.data.PluginRepository
import com.monithome.models.Widget
import androidx.core.graphics.toColorInt
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap

@Composable
fun ChartWidget(pluginId: String, widget: Widget) {
    val stats by PluginRepository.getPluginStats(pluginId).collectAsState()
    val history = PluginRepository.getHistory(pluginId)[widget.dataKey ?: ""] ?: emptyList()
    val key = widget.dataKey ?: ""
    val displayKey = "display_$key"
    val currentValue = (stats[displayKey] ?: stats[key])?.toString() ?: "0"
    
    // Получаем название компонента (процессора или видеокарты)
    val componentName = remember(key, stats) {
        when {
            key.contains("cpu") -> stats["cpu_name"]?.toString()
            key.contains("gpu") -> stats["gpu_name"]?.toString()
            else -> null
        }
    }

    val colorHex = widget.color ?: "#38bdf8"
    val color = try { Color(colorHex.toColorInt()) } catch (e: Exception) { MonitTheme.Primary }

    GlassCard(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        cornerRadius = 16.dp
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!widget.icon.isNullOrEmpty()) {
                        Icon(
                            imageVector = mapIcon(widget.icon),
                            contentDescription = null,
                            tint = color,
                            modifier = Modifier.size(28.dp).padding(end = 10.dp)
                        )
                    }
                    Column {
                        Text(
                            widget.label ?: "", 
                            color = Color.White.copy(alpha = 0.9f), 
                            fontSize = 12.sp, 
                            fontWeight = FontWeight.Medium
                        )
                        if (!componentName.isNullOrEmpty()) {
                            Text(
                                componentName,
                                color = MonitTheme.TextSecondary.copy(alpha = 0.5f),
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                
                Text(
                    text = "$currentValue${if (stats.containsKey(displayKey)) "" else (widget.unit ?: "")}", 
                    color = Color.White, 
                    fontWeight = FontWeight.Black,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Canvas(modifier = Modifier.fillMaxWidth().height(50.dp)) {
                if (history.isEmpty()) return@Canvas
                
                val path = Path()
                val fillPath = Path()
                val width = size.width
                val height = size.height
                val maxVal = 100f 
                
                val points = history.takeLast(30)
                val stepX = width / (30 - 1)
                
                points.forEachIndexed { i, valItem ->
                    val x = i * stepX
                    val y = height - (valItem / maxVal * height).coerceIn(0f, height)
                    
                    if (i == 0) {
                        path.moveTo(x, y)
                        fillPath.moveTo(x, height)
                        fillPath.lineTo(x, y)
                    } else {
                        path.lineTo(x, y)
                        fillPath.lineTo(x, y)
                    }
                    
                    if (i == points.size - 1) {
                        fillPath.lineTo(x, height)
                        fillPath.close()
                    }
                }
                
                // Draw gradient fill
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(color.copy(alpha = 0.3f), Color.Transparent)
                    )
                )
                
                // Draw line
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
    }
}
