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
    GlassCard(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        cornerRadius = 28.dp
    ) {
        Text(
            text = (plugin.name ?: "PLUGIN").uppercase(),
            color = MonitTheme.Primary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        
        // Рендерим виджеты (пропуская unified_media, так как он вынесен наверх)
        plugin.widgets?.filter { it.type != "unified_media" }?.forEach { widget ->
            WidgetRenderer(plugin.id ?: "", widget)
        }
        
        // Рендерим экшены (кнопки и т.д.)
        plugin.actions?.forEach { action ->
            WidgetRenderer(plugin.id ?: "", action)
        }
    }
}

@Composable
fun StatWidget(widget: Widget, stats: Map<String, Any>) {
    val key = widget.dataKey ?: ""
    val displayKey = "display_$key"
    
    // Приоритет отдаем форматированной строке от сервера (например, "8 / 16 GB")
    val valueStr = (stats[displayKey] ?: stats[key])?.toString() ?: "0"
    
    val valueFloat = (stats["${key}_percent"] as? Number)?.toFloat() 
        ?: (stats[key] as? Number)?.toFloat() 
        ?: 0f
    
    val secondaryValue = stats["secondary_$key"]?.toString()
    
    WidgetContainer {
        ValueBlock(
            label = widget.label ?: "",
            value = valueStr,
            unit = if (stats.containsKey(displayKey)) "" else (widget.unit ?: ""),
            icon = if (!widget.icon.isNullOrEmpty()) mapIcon(widget.icon) else null
        )
        AnimatedProgressBar(
            value = valueFloat,
            label = secondaryValue,
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
