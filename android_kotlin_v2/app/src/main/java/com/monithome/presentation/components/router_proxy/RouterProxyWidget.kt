package com.monithome.presentation.components.router_proxy

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monithome.presentation.dashboard.DashboardIntent
import com.monithome.presentation.dashboard.DashboardState
import com.monithome.presentation.dashboard.util.t

@Composable
fun RouterProxyWidget(
    state: DashboardState,
    onIntent: (DashboardIntent) -> Unit,
    modifier: Modifier = Modifier
) {
    val routerStats = state.stats["keenetic_mihomo"] ?: emptyMap()
    
    @Suppress("UNCHECKED_CAST")
    val keenetic = (routerStats["keenetic"] as? Map<String, Any>) ?: emptyMap()
    @Suppress("UNCHECKED_CAST")
    val mihomo = (routerStats["mihomo"] as? Map<String, Any>) ?: emptyMap()

    val kOnline = (keenetic["online"] as? Boolean) ?: false
    val kModel = (keenetic["model"] as? String) ?: "Роутер Keenetic"
    val kCpu = (keenetic["cpu_load"] as? Number)?.toInt() ?: 0
    val kRam = (keenetic["ram_usage"] as? Number)?.toFloat() ?: 0f
    val kClients = (keenetic["clients_count"] as? Number)?.toInt() ?: 0
    val kDown = (keenetic["down_speed_mbits"] as? Number)?.toDouble() ?: 0.0
    val kUp = (keenetic["up_speed_mbits"] as? Number)?.toDouble() ?: 0.0
    @Suppress("UNCHECKED_CAST")
    val repeaters = (keenetic["repeaters"] as? List<Map<String, Any>>) ?: emptyList()

    val mOnline = (mihomo["online"] as? Boolean) ?: false
    val mMode = (mihomo["mode"] as? String) ?: "RULE"
    val mDown = (mihomo["down_speed_mbits"] as? Number)?.toDouble() ?: 0.0
    val mUp = (mihomo["up_speed_mbits"] as? Number)?.toDouble() ?: 0.0

    val accentColor = Color(state.themeColor)

    var showRebootConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            // Заголовок
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Router,
                        contentDescription = "Router",
                        tint = if (accentColor.luminance() < 0.2f) MaterialTheme.colorScheme.onSurface else accentColor,
                        modifier = Modifier.size(24.dp).padding(end = 8.dp)
                    )
                    Text(
                        text = state.t("plugin_name_keenetic_mihomo", "Keenetic & Mihomo"),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                // Кнопка перезагрузки Keenetic
                if (kOnline) {
                    IconButton(
                        onClick = { showRebootConfirm = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Reboot Router",
                            tint = Color.LightGray.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Блок Keenetic
            Text(
                text = kModel,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Индикатор статуса
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(if (kOnline) Color(0xFF22C55E) else Color(0xFFEF4444), CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (kOnline) "Онлайн" else "Офлайн",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (kOnline) {
                        Spacer(modifier = Modifier.width(12.dp))
                        Icon(
                            imageVector = Icons.Filled.Wifi,
                            contentDescription = "Clients",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "$kClients устр.",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            fontSize = 12.sp
                        )
                    }
                }

                if (kOnline) {
                    Text(
                        text = "📥 ${String.format("%.2f", kDown)} / 📤 ${String.format("%.2f", kUp)} Mbps",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (kOnline) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // CPU Gauge
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "ЦП роутера",
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                fontSize = 11.sp
                            )
                            Text(
                                text = "$kCpu%",
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { kCpu / 100f },
                            color = accentColor,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                            gapSize = 0.dp,
                            drawStopIndicator = {}
                        )
                    }

                    // RAM Gauge
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "ОЗУ роутера",
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                fontSize = 11.sp
                            )
                            Text(
                                text = "${kRam.toInt()}%",
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { kRam / 100f },
                            color = accentColor,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                            gapSize = 0.dp,
                            drawStopIndicator = {}
                        )
                    }
                }
            }

            // Ретрансляторы MWS Mesh
            if (kOnline && repeaters.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Ретрансляторы MWS Mesh:",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                repeaters.forEach { rep ->
                    val rName = (rep["name"] as? String) ?: "Ретранслятор"
                    val rIp = (rep["ip"] as? String) ?: ""
                    val rOnline = (rep["online"] as? Boolean) ?: true
                    
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(if (rOnline) Color(0xFF22C55E) else Color(0xFFEF4444), CircleShape)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = rName,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                fontSize = 13.sp
                            )
                        }
                        if (rIp.isNotEmpty()) {
                            Text(
                                text = rIp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // Разделитель
            Divider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), thickness = 1.dp, modifier = Modifier.padding(bottom = 16.dp))

            // Блок Mihomo
            Text(
                text = "Прокси Mihomo",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(if (mOnline) Color(0xFF22C55E) else Color(0xFFEF4444), CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (mOnline) "Движок активен" else "Недоступен",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (mOnline) {
                    Text(
                        text = "⚡ 📥 ${String.format("%.2f", mDown)} / 📤 ${String.format("%.2f", mUp)} Mbps",
                        color = if (accentColor.luminance() < 0.2f) Color.LightGray else accentColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Переключатель режимов прокси
            if (mOnline) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(36.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(8.dp)).border(1.dp, MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val modes = listOf("RULE", "GLOBAL", "DIRECT")
                    modes.forEach { mode ->
                        val isSelected = mMode == mode
                        val btnBg = if (isSelected) accentColor else Color.Transparent
                        val btnTextCol = if (isSelected) {
                            if (accentColor.luminance() < 0.2f) MaterialTheme.colorScheme.onSurface else Color.Black
                        } else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(2.dp)
                                .background(btnBg, RoundedCornerShape(6.dp))
                                .border(if (isSelected) 1.dp else 0.dp, Color.Transparent, RoundedCornerShape(6.dp))
                                .wrapContentSize(Alignment.Center)
                                .height(32.dp)
                                .fillMaxWidth()
                        ) {
                            TextButton(
                                onClick = { 
                                    onIntent(DashboardIntent.PluginCommand(
                                        pluginId = "keenetic_mihomo",
                                        action = "change_mode",
                                        data = mapOf("mode" to mode.lowercase())
                                    ))
                                },
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Text(
                                    text = mode,
                                    color = btnTextCol,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Подтверждение перезагрузки роутера
    if (showRebootConfirm) {
        AlertDialog(
            onDismissRequest = { showRebootConfirm = false },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            title = { Text(state.t("confirm_title", "Подтверждение")) },
            text = { Text("Перезагрузить интернет-роутер Keenetic?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onIntent(DashboardIntent.PluginCommand("keenetic_mihomo", "reboot"))
                        showRebootConfirm = false
                    }
                ) {
                    Text("Да", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRebootConfirm = false }) {
                    Text("Отмена", color = Color.White)
                }
            }
        )
    }
}
