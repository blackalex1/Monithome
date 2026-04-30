package com.monithome.ui

import coil.compose.AsyncImage
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.monithome.data.PluginRepository
import com.monithome.models.LyricTiming
import com.monithome.models.Widget
import com.monithome.network.SocketManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class FlatSource(
    val pluginId: String,
    val deviceId: String,
    val displayName: String
)

@Composable
fun MediaWidget() {
    val allConfigs by PluginRepository.uiConfigs.collectAsState()
    val mediaConfigs = remember(allConfigs) { allConfigs.filter { it.type == "media_source" } }
    
    if (mediaConfigs.isEmpty()) return

    // Собираем все источники в один плоский список
    // Для этого нам нужно наблюдать за статами каждого плагина
    val sources = mutableListOf<FlatSource>()
    mediaConfigs.forEach { config ->
        val pId = config.id ?: ""
        val pStats by PluginRepository.getPluginStats(pId).collectAsState()
        
        @Suppress("UNCHECKED_CAST")
        val devices = pStats["devices"] as? List<Map<String, Any>>
        
        if (devices != null && devices.isNotEmpty()) {
            devices.forEach { dev ->
                sources.add(FlatSource(
                    pluginId = pId,
                    deviceId = dev["id"]?.toString() ?: "all",
                    displayName = dev["name"]?.toString() ?: config.name ?: "Колонка"
                ))
            }
        } else if (devices == null) {
            // Плоский плагин (PC Media)
            sources.add(FlatSource(
                pluginId = pId,
                deviceId = "all",
                displayName = pStats["device_name"]?.toString() ?: config.name ?: "Медиа"
            ))
        }
    }

    if (sources.isEmpty()) return

    var selectedIndex by remember { mutableIntStateOf(0) }
    if (selectedIndex >= sources.size) selectedIndex = 0

    val currentSource = sources[selectedIndex]
    val allStats by PluginRepository.getPluginStats(currentSource.pluginId).collectAsState()

    @Suppress("UNCHECKED_CAST")
    val currentStats: Map<String, Any> = if (currentSource.deviceId != "all") {
        (allStats["devices"] as? List<Map<String, Any>>)
            ?.find { it["id"] == currentSource.deviceId } ?: emptyMap()
    } else {
        allStats
    }

    val title = (currentStats["title"] as? String) 
        ?: (currentStats["track_name"] as? String) 
        ?: (currentStats["text"] as? String) 
        ?: ""
    val artist = (currentStats["artist"] as? String) 
        ?: (currentStats["subtitle"] as? String) 
        ?: (currentStats["author"] as? String) 
        ?: ""
    val isPlaying = currentStats["playing"] as? Boolean ?: false
    val baseProgress = (currentStats["progress"] as? Number)?.toDouble() ?: 0.0
    val duration = (currentStats["duration"] as? Number)?.toDouble() ?: 300.0
    val lastUpdate = (currentStats["local_last_update"] as? Number)?.toDouble() ?: (System.currentTimeMillis() / 1000.0)

    // Локальная интерполяция для идеальной плавности
    var interpolatedProgress by remember { mutableDoubleStateOf(baseProgress) }
    
    LaunchedEffect(baseProgress, isPlaying, lastUpdate) {
        if (!isPlaying) {
            interpolatedProgress = baseProgress
            return@LaunchedEffect
        }
        
        while (true) {
            val now = System.currentTimeMillis() / 1000.0
            val diff = now - lastUpdate
            interpolatedProgress = (baseProgress + diff).coerceIn(0.0, duration)
            kotlinx.coroutines.android.awaitFrame() // Обновляем каждый кадр
        }
    }
    val volume = (currentStats["volume"] as? Number)?.toInt() ?: 0
    val targetId = currentSource.deviceId
    val coverBase64 = currentStats["cover"] as? String ?: ""

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B).copy(alpha = 0.8f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Вкладки выбора источника (теперь по списку sources)
            if (sources.size > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    sources.forEachIndexed { index, source ->
                        val isSelected = index == selectedIndex
                        Surface(
                            onClick = { selectedIndex = index },
                            shape = RoundedCornerShape(20.dp),
                            color = if (isSelected) Color(0xFF38BDF8) else Color.White.copy(alpha = 0.05f),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp)) {
                                Text(
                                    source.displayName,
                                    color = if (isSelected) Color.Black else Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Обложка (адаптивная к пропорциям с мягкой тенью)
                Box(
                    modifier = Modifier
                        .height(64.dp)
                        .widthIn(min = 64.dp, max = 120.dp)
                        .background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(8.dp))
                        .clip(RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (coverBase64.isNotEmpty()) {
                        val model = remember(coverBase64) {
                            if (coverBase64.startsWith("http")) {
                                coverBase64 // Это URL
                            } else if (coverBase64.startsWith("//")) {
                                "https:$coverBase64"
                            } else {
                                try {
                                    // Обязательно возвращаем результат декодирования!
                                    android.util.Base64.decode(coverBase64, android.util.Base64.DEFAULT)
                                } catch (e: Exception) {
                                    null
                                }
                            }
                        }

                        if (model != null) {
                            AsyncImage(
                                model = model,
                                contentDescription = null,
                                modifier = Modifier.fillMaxHeight().wrapContentWidth(),
                                contentScale = ContentScale.Fit, // Сохраняем пропорции без обрезки
                                error = painterResource(id = android.R.drawable.ic_menu_report_image),
                                fallback = painterResource(id = android.R.drawable.ic_menu_gallery)
                            )
                        } else {
                            Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.Gray)
                        }
                    } else {
                        Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.Gray)
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(title.ifEmpty { "Тишина..." }, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(artist.ifEmpty { "—" }, color = Color.Gray, fontSize = 14.sp, maxLines = 1)
                    
                    // Индикатор громкости
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Icon(Icons.Default.VolumeUp, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("$volume%", color = Color(0xFF38BDF8), fontSize = 11.sp)
                    }
                }

                // Кнопки управления
                Row {
                    IconButton(onClick = { SocketManager.sendCommand(currentSource.pluginId, "prev_track", target = targetId) }) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = null, tint = Color.White)
                    }
                    IconButton(onClick = { SocketManager.sendCommand(currentSource.pluginId, "play_pause", target = targetId) }) {
                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                    }
                    IconButton(onClick = { SocketManager.sendCommand(currentSource.pluginId, "next_track", target = targetId) }) {
                        Icon(Icons.Default.SkipNext, contentDescription = null, tint = Color.White)
                    }
                }
            }

            // Прогресс-бар
            Column(modifier = Modifier.padding(vertical = 12.dp)) {
                LinearProgressIndicator(
                    progress = if (duration > 0) (interpolatedProgress / duration).toFloat().coerceIn(0f, 1f) else 0f,
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = Color(0xFF38BDF8),
                    trackColor = Color.DarkGray
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatTime(interpolatedProgress), color = Color.Gray, fontSize = 10.sp)
                    Text(formatTime(duration), color = Color.Gray, fontSize = 10.sp)
                }
            }

            // Ползунок громкости с защитой от "прыжков"
            var localVolume by remember(volume) { mutableFloatStateOf(volume.toFloat()) }
            var lastInteractionTime by remember { mutableLongStateOf(0L) }
            val isInteracting = System.currentTimeMillis() - lastInteractionTime < 1500

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                Icon(Icons.Default.VolumeDown, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(20.dp))
                Slider(
                    value = if (isInteracting) localVolume else volume.toFloat(),
                    onValueChange = { 
                        localVolume = it
                        lastInteractionTime = System.currentTimeMillis()
                        val volInt = it.toInt()
                        SocketManager.sendCommand(currentSource.pluginId, "set_volume:$volInt", target = targetId)
                    },
                    valueRange = 0f..100f,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF38BDF8),
                        activeTrackColor = Color(0xFF38BDF8),
                        inactiveTrackColor = Color.DarkGray
                    )
                )
                Icon(Icons.Default.VolumeUp, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(20.dp))
            }
            
            // Кнопка открытия текста (только для Яндекса)
            if (currentSource.pluginId.contains("yandex", ignoreCase = true)) {
                var showLyrics by remember { mutableStateOf(false) }
                Button(
                    onClick = { showLyrics = true },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8).copy(alpha = 0.1f))
                ) {
                    Text("ТЕКСТ ПЕСНИ", color = Color(0xFF38BDF8))
                }

                if (showLyrics) {
                    LyricsDialog(targetId, currentStats) { showLyrics = false }
                }
            }
        }
    }
}

fun formatTime(seconds: Double): String {
    val totalSeconds = seconds.toLong()
    val mins = totalSeconds / 60
    val secs = totalSeconds % 60
    return "%02d:%02d".format(mins, secs)
}
