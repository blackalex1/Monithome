package com.monithome.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monithome.models.Widget

@Composable
fun ListWidget(widget: Widget, stats: Map<String, Any>) {
    val lKey = widget.listKey ?: ""
    @Suppress("UNCHECKED_CAST")
    val items = stats[lKey] as? List<Map<String, Any>> ?: emptyList()

    WidgetContainer {
        Text(widget.label ?: "", color = Color.Gray, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(8.dp))
        items.forEach { item ->
            val device = item["device"]?.toString() ?: "—"
            val labelStr = item["label"]?.toString() ?: "Диск"
            val percent = (item["percent"] as? Number)?.toFloat() ?: 0f
            val freeText = item["free_text"]?.toString() ?: ""
            
            ValueBlock(
                label = "$device ($labelStr)", 
                value = "${percent.toInt()}%",
                secondaryValue = freeText
            )
            AnimatedProgressBar(value = percent)
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
