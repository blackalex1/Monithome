package com.monithome.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monithome.data.PluginRepository
import com.monithome.models.Widget

@Composable
fun ChartWidget(pluginId: String, widget: Widget) {
    // Подписываемся на статистику (для текущего значения)
    val stats by PluginRepository.getPluginStats(pluginId).collectAsState()
    // Подписываемся на историю (для линии графика)
    val history = PluginRepository.getHistory(pluginId)[widget.dataKey ?: ""] ?: emptyList()
    
    val currentValue = stats[widget.dataKey ?: ""]?.toString() ?: "0"
    val colorHex = widget.color ?: "#38bdf8"
    val color = Color(android.graphics.Color.parseColor(colorHex))

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B).copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(widget.label ?: "", color = Color.Gray, fontSize = 14.sp)
                Text("$currentValue ${widget.unit ?: ""}", color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Нативное рисование графика на Canvas
            Canvas(modifier = Modifier.fillMaxWidth().height(40.dp)) {
                if (history.size < 2) return@Canvas
                
                val path = Path()
                val width = size.width
                val height = size.height
                val maxVal = 100f // Для процентов. Для температур можно вычислять динамически
                
                val stepX = width / (30 - 1) // Макс 30 точек
                
                history.forEachIndexed { i, valItem ->
                    val x = i * stepX
                    val y = height - (valItem / maxVal * height)
                    
                    if (i == 0) path.moveTo(x, y)
                    else path.lineTo(x, y)
                }
                
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }
    }
}
