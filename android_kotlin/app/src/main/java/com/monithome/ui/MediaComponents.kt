package com.monithome.ui

import coil.compose.AsyncImage
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monithome.data.PluginRepository
import com.monithome.network.SocketManager
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

    // 1. Источники (Оптимизировано: пересчет только при изменении списка устройств)
    val sources by produceState<List<FlatSource>>(initialValue = emptyList(), mediaConfigs) {
        // Подписываемся ТОЛЬКО на список устройств каждого медиа-плагина
        val flows = mediaConfigs.map { config -> 
            PluginRepository.getPluginStats(config.id ?: "").map { stats ->
                stats["devices"] to (stats["device_name"] ?: config.name)
            }.distinctUntilChanged()
        }
        
        kotlinx.coroutines.flow.combine(flows) { statsArray ->
            val list = mutableListOf<FlatSource>()
            statsArray.forEachIndexed { index, (devicesRaw, deviceName) ->
                val config = mediaConfigs[index]
                val pId = config.id ?: ""
                @Suppress("UNCHECKED_CAST")
                val devices = devicesRaw as? List<Map<String, Any>>
                
                if (!devices.isNullOrEmpty()) {
                    devices.forEach { dev ->
                        val dName = dev["name"]?.toString() ?: deviceName?.toString() ?: (if (pId == "pc_media") "PC Media" else "Device")
                        list.add(FlatSource(pId, dev["id"]?.toString() ?: "all", dName))
                    }
                } else {
                    val dName = deviceName?.toString() ?: (if (pId == "pc_media") "PC Media" else "Media")
                    list.add(FlatSource(pId, "all", dName))
                }
            }
            list.sortByDescending { it.pluginId == "pc_media" }
            list
        }.distinctUntilChanged { old, new -> 
            old.size == new.size && old.zip(new).all { (o, n) -> 
                o.pluginId == n.pluginId && o.deviceId == n.deviceId && o.displayName == n.displayName 
            }
        }.collect { value = it }
    }

    if (sources.isEmpty()) return

    var selectedSourceKey by remember { mutableStateOf("") }
    
    // Автовыбор первого источника (обычно PC Media) при инициализации или если текущий пропал
    LaunchedEffect(sources) {
        if (selectedSourceKey.isEmpty() || sources.none { "${it.pluginId}:${it.deviceId}" == selectedSourceKey }) {
            if (sources.isNotEmpty()) {
                selectedSourceKey = "${sources[0].pluginId}:${sources[0].deviceId}"
            }
        }
    }

    val currentSource = remember(sources, selectedSourceKey) {
        sources.find { "${it.pluginId}:${it.deviceId}" == selectedSourceKey } ?: sources.getOrNull(0)
    }
    
    if (currentSource == null) return

    // 2. Изолируем поток данных для текущего источника (устройства)
    val deviceStatsFlow = remember(currentSource) {
        PluginRepository.getPluginStats(currentSource.pluginId).map { stats ->
            if (currentSource.deviceId != "all") {
                @Suppress("UNCHECKED_CAST")
                (stats["devices"] as? List<Map<String, Any>>)
                    ?.find { it["id"] == currentSource.deviceId } ?: emptyMap()
            } else {
                stats
            }
        }.distinctUntilChanged()
    }

    // 3. Точечные подписки для минимизации рекомпозиций всего виджета
    val trackTitle by remember(deviceStatsFlow) {
        deviceStatsFlow.map { (it["title"] as? String) ?: (it["track_name"] as? String) ?: "" }.distinctUntilChanged()
    }.collectAsState(initial = "")

    val trackArtist by remember(deviceStatsFlow) {
        deviceStatsFlow.map { (it["artist"] as? String) ?: (it["subtitle"] as? String) ?: "" }.distinctUntilChanged()
    }.collectAsState(initial = "")

    val isPlaying by remember(deviceStatsFlow) {
        deviceStatsFlow.map { it["playing"] as? Boolean ?: false }.distinctUntilChanged()
    }.collectAsState(initial = false)

    val coverBase64 by remember(deviceStatsFlow) {
        deviceStatsFlow.map { it["cover"] as? String ?: "" }.distinctUntilChanged()
    }.collectAsState(initial = "")

    val playbackData by remember(deviceStatsFlow) {
        deviceStatsFlow.map { stats ->
            Triple(
                (stats["progress"] as? Number)?.toDouble() ?: 0.0,
                (stats["duration"] as? Number)?.toDouble() ?: 0.0,
                (stats["local_last_update"] as? Number)?.toDouble() ?: (System.currentTimeMillis() / 1000.0)
            )
        }.distinctUntilChanged()
    }.collectAsState(initial = Triple(0.0, 0.0, 0.0))

    val volume by remember(deviceStatsFlow) {
        deviceStatsFlow.map { (it["volume"] as? Number)?.toInt() ?: 0 }.distinctUntilChanged()
    }.collectAsState(initial = 0)

    GlassCard(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), cornerRadius = 32.dp) {
        if (sources.size > 1) {
            MediaSourceSelector(sources, selectedSourceKey) { selectedSourceKey = it }
        }

        MediaTrackInfo(currentSource, trackTitle, trackArtist, isPlaying, coverBase64)

        // Прогресс (Изолированная зона с 10Hz обновлением)
        PlaybackSection(playbackData, isPlaying)

        // Громкость (Изолированная зона)
        MediaVolumeControl(currentSource, volume)
    }
}

@Composable
fun MediaTrackInfo(currentSource: FlatSource, title: String, artist: String, isPlaying: Boolean, coverBase64: String) {
    val allConfigs by PluginRepository.uiConfigs.collectAsState()
    val isLyricsActive by remember { derivedStateOf { allConfigs.any { it.id == "yandex_lyrics" && it.active == true } } }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        MediaCover(coverBase64)
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title.ifEmpty { "..." }, color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1)
            Text(artist.ifEmpty { "—" }, color = MonitTheme.TextSecondary, fontSize = 12.sp, maxLines = 1)
        }
        Spacer(modifier = Modifier.width(8.dp))
        MediaControls(currentSource, isPlaying, isLyricsActive)
    }
}

@Composable
fun MediaCover(cover: String) {
    Box(
        modifier = Modifier.size(70.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.05f)),
        contentAlignment = Alignment.Center
    ) {
        if (cover.isNotEmpty()) {
            val context = LocalContext.current
            val model by produceState<Any?>(initialValue = null, cover) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    if (cover.startsWith("http")) cover
                    else if (cover.startsWith("//")) "https:$cover"
                    else {
                        try {
                            val clean = if (cover.contains(",")) cover.substringAfter(",") else cover
                            android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
                        } catch (e: Exception) {
                            android.util.Log.e("MediaCover", "Failed to decode base64 cover")
                            null
                        }
                    }
                }
            }
            
            if (model != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(model)
                        .size(128)
                        .crossfade(true)
                        .allowHardware(true)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    onError = { android.util.Log.e("MediaCover", "Coil error: ${it.result.throwable.message}") }
                )
            }
        } else {
            Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.Gray)
        }
    }
}

@Composable
fun PlaybackSection(playbackData: Triple<Double, Double, Double>, isPlaying: Boolean) {
    val (baseProgress, duration, lastUpdate) = playbackData
    
    var interpolatedProgress by remember { mutableDoubleStateOf(baseProgress) }
    
    LaunchedEffect(baseProgress, isPlaying) {
        interpolatedProgress = baseProgress
    }
    
    if (isPlaying && duration > 0) {
        LaunchedEffect(lastUpdate) {
            while (true) {
                val now = System.currentTimeMillis() / 1000.0
                interpolatedProgress = (baseProgress + (now - lastUpdate)).coerceIn(0.0, duration)
                kotlinx.coroutines.delay(100) 
            }
        }
    }

    MediaProgressBar(
        progress = if (duration > 0) (interpolatedProgress / duration).toFloat() else 0f,
        currentTime = formatTime(interpolatedProgress),
        durationTime = formatTime(duration)
    )
}

@Composable
fun MediaProgressBar(progress: Float, currentTime: String, durationTime: String) {
    val barBrush = remember { Brush.horizontalGradient(listOf(MonitTheme.Primary, MonitTheme.Secondary)) }
    Column(modifier = Modifier.padding(top = 16.dp)) {
        Box(modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.05f))) {
            Box(modifier = Modifier.fillMaxWidth(progress).fillMaxHeight().background(barBrush))
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(currentTime, color = MonitTheme.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.weight(1f))
            Text(durationTime, color = MonitTheme.TextSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
fun MediaSourceSelector(sources: List<FlatSource>, selectedKey: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        sources.forEach { source ->
            val key = "${source.pluginId}:${source.deviceId}"
            val isSelected = key == selectedKey
            Surface(
                onClick = { onSelect(key) },
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

@Composable
fun MediaControls(currentSource: FlatSource, isPlaying: Boolean, isLyricsActive: Boolean) {
    val targetId = currentSource.deviceId
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (currentSource.pluginId.contains("yandex", ignoreCase = true) && isLyricsActive) {
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
                        fontWeight = FontWeight.Black, color = MonitTheme.Primary, letterSpacing = 1.sp
                    )
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
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

@Composable
fun MediaVolumeControl(currentSource: FlatSource, volume: Int) {
    val targetId = currentSource.deviceId
    var localVolume by remember { mutableFloatStateOf(volume.toFloat()) }
    var lastInteractionTime by remember { mutableLongStateOf(0L) }
    val isInteracting = System.currentTimeMillis() - lastInteractionTime < 2000

    LaunchedEffect(volume) {
        if (!isInteracting) localVolume = volume.toFloat()
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
        Icon(Icons.AutoMirrored.Filled.VolumeDown, contentDescription = null, tint = MonitTheme.TextSecondary, modifier = Modifier.size(18.dp))
        Slider(
            value = localVolume,
            onValueChange = { localVolume = it; lastInteractionTime = System.currentTimeMillis() },
            onValueChangeFinished = { SocketManager.sendCommand(currentSource.pluginId, "set_volume:${localVolume.toInt()}", target = targetId) },
            valueRange = 0f..100f,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
        )
        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = MonitTheme.TextSecondary, modifier = Modifier.size(18.dp))
    }
}

fun formatTime(seconds: Double): String {
    val totalSeconds = seconds.toLong()
    val hours = totalSeconds / 3600
    val mins = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, mins, secs) else "%02d:%02d".format(mins, secs)
}
