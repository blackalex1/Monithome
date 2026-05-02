package com.monithome.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.monithome.data.PluginRepository
import com.monithome.models.PluginInfo
import com.monithome.network.SocketManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen() {
    val uiConfigs by PluginRepository.uiConfigs.collectAsState()
    LaunchedEffect(uiConfigs.size) {
        android.util.Log.i("DashboardScreen", "uiConfigs changed! size: ${uiConfigs.size}")
    }
    val currentLanguage by com.monithome.data.LanguageManager.currentLanguage.collectAsState()
    val translations by com.monithome.data.LanguageManager.translations.collectAsState()
    val activeLyrics by PluginRepository.activeLyrics.collectAsState()
    val strings = com.monithome.data.Strings

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedBackground()

        val systemStats by PluginRepository.getPluginStats("system_stats").collectAsState()
        val isConnected by SocketManager.isConnected.collectAsState()
        val hostname = systemStats["hostname"]?.toString() ?: "PC"
        val osName = systemStats["os"]?.toString() ?: "Windows"

        Scaffold(
            containerColor = Color.Transparent
        ) { padding ->
            if (uiConfigs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MonitTheme.Primary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(strings.waitingData, color = MonitTheme.TextSecondary)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 72.dp), // Увеличили верхний отступ для шапки
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(uiConfigs, key = { it.id ?: it.hashCode().toString() }) { plugin ->
                        val hasMedia = plugin.widgets?.any { it.type == "unified_media" } ?: false
                        val otherWidgets = plugin.widgets?.filter { it.type != "unified_media" } ?: emptyList()
                        val hasOtherUi = otherWidgets.isNotEmpty() || !plugin.actions.isNullOrEmpty()
                        
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (hasMedia) {
                                MediaWidget()
                            }
                            
                            if (hasOtherUi) {
                                AnimatedVisibility(
                                    visible = true,
                                    enter = fadeIn() + expandVertically()
                                ) {
                                    PluginCard(plugin)
                                }
                            }
                        }
                    }
                    
                    item {
                        Spacer(modifier = Modifier.height(100.dp))
                    }
                }
            }
        }

        // ШАПКА (полностью прозрачная, чтобы не создавать "плашку")
        Surface(
            color = Color.Transparent,
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 16.dp)
            ) {
                // Центр: Логотип
                Text(
                    "MONITHOME",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                        color = Color.White
                    )
                )

                // Право: Системная инфо и Статус
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            hostname.uppercase(),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = MonitTheme.Primary,
                                letterSpacing = 1.sp
                            )
                        )
                        Text(
                            osName,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp,
                                color = MonitTheme.TextSecondary
                            )
                        )
                    }
                    
                    Box(
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isConnected) Color.Green else Color.Red)
                    )
                }
            }
        }

        // ПОЛНОЭКРАННЫЙ ТЕКСТ ПЕСНИ (Поверх всего, с явным zIndex)
        activeLyrics?.let { lyricsState ->
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(animationSpec = tween(500)),
                exit = fadeOut(animationSpec = tween(500)),
                modifier = Modifier.zIndex(10f)
            ) {
                LyricsFullscreenView(
                    pluginId = lyricsState.pluginId,
                    deviceId = lyricsState.deviceId,
                    onDismiss = { PluginRepository.hideLyrics() }
                )
            }
        }
    }
}
