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
        Text(
            widget.getLocalizedLabel().uppercase(), 
            color = MonitTheme.TextSecondary, 
            fontSize = 10.sp, 
            fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val buttons = if (serverButtons.isNotEmpty()) {
                serverButtons.map { 
                    ButtonInfo(
                        it.getLocalizedLabel(), 
                        it.action ?: it.dataKey ?: it.id ?: "",
                        mapIcon(it.icon)
                    )
                }
            } else {
                listOf(
                    ButtonInfo("SLEEP", "sleep", Icons.Default.Bedtime),
                    ButtonInfo("OFF", "shutdown", Icons.Default.PowerSettingsNew),
                    ButtonInfo("LOCK", "lock", Icons.Default.Lock),
                    ButtonInfo("REBOOT", "restart", Icons.Default.Refresh)
                )
            }

            var showConfirmDialog by remember { mutableStateOf<ButtonInfo?>(null) }
            val strings = com.monithome.data.Strings
            
            if (showConfirmDialog != null) {
                AlertDialog(
                    onDismissRequest = { showConfirmDialog = null },
                    title = { Text(strings.confirmAction) },
                    text = { Text("${strings.sureExecute} \"${showConfirmDialog?.label}\"?") },
                    confirmButton = {
                        TextButton(onClick = {
                            SocketManager.sendCommand(pluginId, showConfirmDialog!!.action)
                            showConfirmDialog = null
                        }) {
                            Text(strings.yes, color = MonitTheme.Primary)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showConfirmDialog = null }) {
                            Text(strings.cancel)
                        }
                    }
                )
            }

            buttons.forEach { btn ->
                val needsConfirm = serverButtons.find { (it.action ?: it.id) == btn.action }?.needConfirm ?: false
                
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .clickable { 
                            if (needsConfirm) {
                                showConfirmDialog = btn
                            } else {
                                SocketManager.sendCommand(pluginId, btn.action)
                            }
                        }
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        btn.icon, 
                        contentDescription = null, 
                        tint = MonitTheme.Primary, 
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        btn.label.uppercase(), 
                        color = Color.White, 
                        fontSize = 9.sp, 
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
