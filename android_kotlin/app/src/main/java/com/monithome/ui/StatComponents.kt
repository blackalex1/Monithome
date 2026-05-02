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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap

import com.monithome.utils.resolveStat
import com.monithome.utils.toComposeColor

@Composable
fun ChartWidget(pluginId: String, widget: Widget) {
    val stats by PluginRepository.getPluginStats(pluginId).collectAsState()
    val history = PluginRepository.getHistory(pluginId)[widget.dataKey ?: ""] ?: emptyList()
    val key = widget.dataKey ?: ""
    val currentValue = stats.resolveStat(key, widget.unit)
    
    // Получаем название компонента (процессора или видеокарты)
    val componentName = remember(key, stats) {
        when {
            key.contains("cpu") -> stats["cpu_name"]?.toString()
            key.contains("gpu") -> stats["gpu_name"]?.toString()
            else -> null
        }
    }

    val color = widget.color.toComposeColor()

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
                    text = currentValue, 
                    color = Color.White, 
                    fontWeight = FontWeight.Black,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Canvas(modifier = Modifier.fillMaxWidth().height(55.dp)) {
                if (history.isEmpty()) return@Canvas
                
                val path = Path()
                val fillPath = Path()
                val width = size.width
                val height = size.height
                
                val points = history.takeLast(40) // Берем чуть больше точек для плавности
                val stepX = width / (points.size - 1).coerceAtLeast(1)
                
                // Динамическое масштабирование: ищем максимум в истории
                val historyMax = points.maxOrNull() ?: 100f
                // Если это проценты - до 100, если нет (напр. ГБ) - берем с запасом 10%
                val maxVal = if (historyMax <= 100f && widget.unit == "%") 100f else historyMax * 1.1f
                
                points.forEachIndexed { i, valItem ->
                    val x = i * stepX
                    val y = (height - (valItem / maxVal * height)).coerceIn(0f, height)
                    
                    if (i == 0) {
                        path.moveTo(x, y)
                        fillPath.moveTo(x, height)
                        fillPath.lineTo(x, y)
                    } else {
                        val prevX = (i - 1) * stepX
                        val prevY = (height - (points[i - 1] / maxVal * height)).coerceIn(0f, height)
                        
                        // Cubic Bezier для сглаживания
                        path.cubicTo(
                            prevX + (x - prevX) / 2f, prevY,
                            prevX + (x - prevX) / 2f, y,
                            x, y
                        )
                        fillPath.cubicTo(
                            prevX + (x - prevX) / 2f, prevY,
                            prevX + (x - prevX) / 2f, y,
                            x, y
                        )
                    }
                    
                    if (i == points.size - 1) {
                        fillPath.lineTo(x, height)
                        fillPath.close()
                    }
                }
                
                // Draw gradient fill with more depth
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            color.copy(alpha = 0.4f),
                            color.copy(alpha = 0.1f),
                            Color.Transparent
                        )
                    )
                )
                
                // Draw smooth line
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(
                        width = 2.5.dp.toPx(), 
                        cap = StrokeCap.Round,
                        join = androidx.compose.ui.graphics.StrokeJoin.Round
                    )
                )
            }
        }
    }
}
