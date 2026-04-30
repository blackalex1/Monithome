package com.monithome.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen() {
    // Подписываемся на список конфигураций плагинов
    val uiConfigs by PluginRepository.uiConfigs.collectAsState()

    Scaffold(
        containerColor = Color(0xFF0F172A), // Темный фон как в JS
        topBar = {
            TopAppBar(
                title = { Text("MonitHome Native", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1E293B))
            )
        }
    ) { padding ->
        if (uiConfigs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF38BDF8))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Ожидание данных от сервера... (Найдено: ${uiConfigs.size})", color = Color.Gray)
                }
            }
        } else {
            // Нативный аналог FlatList - теперь полностью динамический
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Флаг, чтобы отрисовать общий медиа-центр только один раз
                var mediaWidgetRendered = false

                items(uiConfigs, key = { "${it.id}_${uiConfigs.indexOf(it)}" }) { plugin ->
                    val isMedia = plugin.widgets?.any { it.type == "unified_media" } ?: false
                    
                    if (isMedia) {
                        if (!mediaWidgetRendered) {
                            MediaWidget()
                            mediaWidgetRendered = true
                        }
                    } else {
                        // Обычный плагин (stats, disks, etc)
                        val hasUi = !plugin.widgets.isNullOrEmpty() || !plugin.actions.isNullOrEmpty()
                        if (hasUi) {
                            PluginCard(plugin)
                        }
                    }
                }
            }
        }
    }
}
