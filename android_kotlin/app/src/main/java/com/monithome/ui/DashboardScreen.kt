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
            // Нативный аналог FlatList - очень быстрый и независимый
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. Сначала рисуем общий Медиа Центр (он сам найдет источники)
                item {
                    MediaWidget()
                }

                item {
                    Text("МОНИТОРИНГ", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                // 2. Показываем плагины (те, у которых есть или виджеты, или экшены)
                val visiblePlugins = uiConfigs.filter { plugin ->
                    val hasUi = !plugin.widgets.isNullOrEmpty() || !plugin.actions.isNullOrEmpty()
                    val isNotOnlyMedia = plugin.widgets?.none { it.type == "unified_media" } ?: true
                    hasUi && isNotOnlyMedia
                }
                
                items(visiblePlugins) { plugin ->
                    PluginCard(plugin)
                }
            }
        }
    }
}
