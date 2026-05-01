package com.monithome.ui

import coil.compose.AsyncImage
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaWidget() {
    val allConfigs by PluginRepository.uiConfigs.collectAsState()
    val mediaConfigs = remember(allConfigs) { allConfigs.filter { it.type == "media_source" } }
    
    if (mediaConfigs.isEmpty()) return

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
                    displayName = dev["name"]?.toString() ?: config.name ?: com.monithome.data.LanguageManager.i18n("speaker")
                ))
            }
        } else {
            // Если список пуст или null (например, плагин только запустился), 
            // добавляем его как единый источник, чтобы вкладка не пропадала.
            sources.add(FlatSource(
                pluginId = pId,
                deviceId = "all",
                displayName = pStats["device_name"]?.toString() ?: config.name ?: com.monithome.data.LanguageManager.i18n("media")
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

    val title = (currentStats["title"] as? String) ?: (currentStats["track_name"] as? String) ?: (currentStats["text"] as? String) ?: ""
    val artist = (currentStats["artist"] as? String) ?: (currentStats["subtitle"] as? String) ?: (currentStats["author"] as? String) ?: ""
    val isPlaying = currentStats["playing"] as? Boolean ?: false
    val baseProgress = (currentStats["progress"] as? Number)?.toDouble() ?: 0.0
    val duration = (currentStats["duration"] as? Number)?.toDouble() ?: 0.0
    val lastUpdate = (currentStats["local_last_update"] as? Number)?.toDouble() ?: (System.currentTimeMillis() / 1000.0)

    var interpolatedProgress by remember { mutableDoubleStateOf(baseProgress) }
    
    // Сброс прогресса при смене трека или резком скачке (более 5 сек)
    LaunchedEffect(title, baseProgress) {
        if (title.isEmpty()) {
            interpolatedProgress = 0.0
        } else {
            interpolatedProgress = baseProgress
        }
    }
    
    LaunchedEffect(baseProgress, isPlaying, lastUpdate) {
        if (!isPlaying) {
            interpolatedProgress = baseProgress
            return@LaunchedEffect
        }
        while (true) {
            val now = System.currentTimeMillis() / 1000.0
            val diff = now - lastUpdate
            interpolatedProgress = (baseProgress + diff).coerceIn(0.0, duration)
            kotlinx.coroutines.android.awaitFrame()
        }
    }
    val volume = (currentStats["volume"] as? Number)?.toInt() ?: 0
    val targetId = currentSource.deviceId
    val coverBase64 = currentStats["cover"] as? String ?: ""

    GlassCard(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        cornerRadius = 32.dp
    ) {
        // Источники
        if (sources.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                sources.forEachIndexed { index, source ->
                    val isSelected = index == selectedIndex
                    Surface(
                        onClick = { selectedIndex = index },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) MonitTheme.Primary else Color.White.copy(alpha = 0.05f),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp)) {
                            Text(
                                source.displayName.uppercase(),
                                color = if (isSelected) Color.Black else Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            // Обложка
            Box(
                modifier = Modifier
                    .height(70.dp)
                    .widthIn(min = 70.dp, max = 110.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.05f)),
                contentAlignment = Alignment.Center
            ) {
                if (coverBase64.isNotEmpty()) {
                    val model = remember(coverBase64) {
                        if (coverBase64.startsWith("http")) coverBase64
                        else if (coverBase64.startsWith("//")) "https:$coverBase64"
                        else {
                            try {
                                val cleanBase64 = if (coverBase64.contains(",")) {
                                    coverBase64.substringAfter(",")
                                } else coverBase64
                                android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
                            } catch (e: Exception) {
                                null
                            }
                        }
                    }

                    if (model != null) {
                        android.util.Log.d("MediaWidget", "Loading cover from: $model")
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(model)
                                .crossfade(true)
                                .allowHardware(false)
                                .build(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxHeight().wrapContentWidth(),
                            contentScale = ContentScale.Fit,
                            error = painterResource(android.R.drawable.ic_menu_report_image),
                            placeholder = painterResource(android.R.drawable.ic_menu_gallery)
                        )
                    } else {
                        Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.Gray)
                    }
                } else {
                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.Gray)
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Инфо о треке
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title.ifEmpty { com.monithome.data.LanguageManager.i18n("waiting") },
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1
                )
                Text(
                    artist.ifEmpty { "—" },
                    color = MonitTheme.TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }
            
            Spacer(modifier = Modifier.width(8.dp))

            // Кнопки управления и текст песни
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (currentSource.pluginId.contains("yandex", ignoreCase = true)) {
                    Box(
                        modifier = Modifier
                            .padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, MonitTheme.Primary.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            .background(MonitTheme.Primary.copy(alpha = 0.1f))
                            .clickable { PluginRepository.showLyrics(currentSource.pluginId, targetId) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            com.monithome.data.LanguageManager.i18n("lyrics"),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Black,
                                color = MonitTheme.Primary,
                                letterSpacing = 1.sp
                            )
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = { SocketManager.sendCommand(currentSource.pluginId, "prev_track", target = targetId) },
                        modifier = Modifier.size(36.dp).background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                    ) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    
                    Surface(
                        onClick = { SocketManager.sendCommand(currentSource.pluginId, "play_pause", target = targetId) },
                        shape = RoundedCornerShape(12.dp),
                        color = MonitTheme.Primary,
                        modifier = Modifier.size(44.dp),
                        shadowElevation = 4.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = { SocketManager.sendCommand(currentSource.pluginId, "next_track", target = targetId) },
                        modifier = Modifier.size(36.dp).background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                    ) {
                        Icon(Icons.Default.SkipNext, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        // Прогресс
        Column(modifier = Modifier.padding(top = 16.dp)) {
            val progress = if (duration > 0.0) (interpolatedProgress / duration).toFloat().coerceIn(0f, 1f) else 0f
            
            Box(modifier = Modifier.fillMaxWidth().height(4.dp)) {
                Box(modifier = Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(2.dp)))
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress)
                        .background(
                            Brush.horizontalGradient(listOf(MonitTheme.Primary, MonitTheme.Secondary)),
                            RoundedCornerShape(2.dp)
                        )
                )
            }
            
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatTime(interpolatedProgress), color = MonitTheme.TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(formatTime(duration), color = MonitTheme.TextSecondary, fontSize = 10.sp)
            }
        }

        // Громкость
        var localVolume by remember { mutableFloatStateOf(volume.toFloat()) }
        var lastInteractionTime by remember { mutableLongStateOf(0L) }
        val isInteracting = System.currentTimeMillis() - lastInteractionTime < 2000

        // Синхронизация с сетью, только если пользователь не трогает ползунок
        LaunchedEffect(volume) {
            if (!isInteracting) {
                localVolume = volume.toFloat()
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
            Icon(Icons.Default.VolumeDown, contentDescription = null, tint = MonitTheme.TextSecondary, modifier = Modifier.size(18.dp))
            Slider(
                value = localVolume,
                onValueChange = { 
                    localVolume = it
                    lastInteractionTime = System.currentTimeMillis()
                },
                onValueChangeFinished = {
                    SocketManager.sendCommand(currentSource.pluginId, "set_volume:${localVolume.toInt()}", target = targetId)
                },
                valueRange = 0f..100f,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                thumb = {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .background(MonitTheme.Primary, CircleShape)
                            .border(2.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                    )
                },
                track = { sliderState ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .drawBehind {
                                val width = size.width
                                val height = size.height
                                val fraction = (sliderState.value - sliderState.valueRange.start) / 
                                              (sliderState.valueRange.endInclusive - sliderState.valueRange.start)
                                val activeWidth = width * fraction

                                // Неактивная часть (вся полоска)
                                drawRoundRect(
                                    color = Color.White.copy(alpha = 0.1f),
                                    size = size,
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(height / 2)
                                )

                                // Активная часть (до центра пунсона)
                                if (activeWidth > 0) {
                                    drawRoundRect(
                                        color = MonitTheme.Primary,
                                        size = androidx.compose.ui.geometry.Size(activeWidth + (height / 2), height), // Небольшой оверлап
                                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(height / 2)
                                    )
                                }
                            }
                    )
                }
            )
            Icon(Icons.Default.VolumeUp, contentDescription = null, tint = MonitTheme.TextSecondary, modifier = Modifier.size(18.dp))
        }
    }
}

fun formatTime(seconds: Double): String {
    val totalSeconds = seconds.toLong()
    val mins = totalSeconds / 60
    val secs = totalSeconds % 60
    return "%02d:%02d".format(mins, secs)
}
