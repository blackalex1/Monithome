package com.monithome.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monithome.network.SocketManager
import com.monithome.models.Widget

@Composable
fun ButtonGroupWidget(pluginId: String, widget: Widget) {
    data class ButtonInfo(val label: String, val action: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
    
    val serverButtons = widget.children ?: emptyList()
    
    WidgetContainer {
        Text(widget.label ?: "Действия", color = Color.Gray, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(12.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val buttons = if (serverButtons.isNotEmpty()) {
                serverButtons.map { 
                    ButtonInfo(
                        it.label ?: it.id ?: "", 
                        it.dataKey ?: it.id ?: "",
                        mapIcon(it.icon)
                    )
                }
            } else {
                // Дефолтные кнопки на случай отсутствия конфига
                listOf(
                    ButtonInfo("Сон", "sleep", Icons.Default.Bedtime),
                    ButtonInfo("Выкл", "shutdown", Icons.Default.PowerSettingsNew),
                    ButtonInfo("Блок", "lock", Icons.Default.Lock),
                    ButtonInfo("Рестарт", "restart", Icons.Default.Refresh)
                )
            }

            buttons.forEach { btn ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .clickable { SocketManager.sendCommand(pluginId, btn.action) }
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(btn.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Text(btn.label, color = Color.White, fontSize = 10.sp, maxLines = 1)
                }
            }
        }
    }
}
