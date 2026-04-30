package com.monithome.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monithome.data.PluginRepository
import com.monithome.models.PluginInfo
import com.monithome.models.Widget

@Composable
fun WidgetRenderer(pluginId: String, widget: Widget) {
    val stats by PluginRepository.getPluginStats(pluginId).collectAsState()

    when (widget.type) {
        "stat" -> StatWidget(widget, stats)
        "row" -> RowWidget(pluginId, widget)
        "unified_media" -> MediaWidget()
        "chart" -> ChartWidget(pluginId, widget)
        "list" -> ListWidget(widget, stats)
        "button_group" -> ButtonGroupWidget(pluginId, widget)
        else -> Text("Unknown widget: ${widget.type}", color = Color.Gray)
    }
}

@Composable
fun PluginCard(plugin: PluginInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1E293B).copy(alpha = 0.6f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = plugin.name ?: "Плагин",
                color = Color(0xFF38BDF8),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            plugin.widgets?.forEach { widget ->
                WidgetRenderer(plugin.id ?: "", widget)
            }
            plugin.actions?.forEach { action ->
                WidgetRenderer(plugin.id ?: "", action)
            }
        }
    }
}

@Composable
fun StatWidget(widget: Widget, stats: Map<String, Any>) {
    val valueStr = stats[widget.dataKey ?: ""]?.toString() ?: "0"
    val valueFloat = (stats["${widget.dataKey}_percent"] as? Number)?.toFloat() 
        ?: (stats[widget.dataKey ?: ""] as? Number)?.toFloat() 
        ?: 0f
    
    WidgetContainer {
        ValueBlock(
            label = widget.label ?: "",
            value = valueStr,
            unit = widget.unit ?: ""
        )
        AnimatedProgressBar(
            value = valueFloat,
            colorRanges = widget.colorRanges
        )
    }
}

@Composable
fun RowWidget(pluginId: String, widget: Widget) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        widget.children?.forEach { child ->
            Box(modifier = Modifier.weight(1f)) {
                WidgetRenderer(pluginId, child)
            }
        }
    }
}
