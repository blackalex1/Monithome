package com.monithome.presentation.components.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.monithome.domain.usecase.ObserveMediaProgressUseCase
import com.monithome.presentation.dashboard.DashboardIntent
import com.monithome.presentation.dashboard.MediaSource
import com.monithome.presentation.dashboard.MediaUIState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

@Composable
fun MediaWidget(
    state: MediaUIState,
    onIntent: (DashboardIntent) -> Unit,
    modifier: Modifier = Modifier
) {
    if (state.sources.isEmpty()) return

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Source Selector
            if (state.sources.size > 1) {
                MediaSourceSelector(
                    sources = state.sources,
                    selectedId = state.selectedSourceId,
                    onSelect = { onIntent(DashboardIntent.SelectMediaSource(it)) }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Track Info & Controls
            Row(verticalAlignment = Alignment.CenterVertically) {
                MediaCover(coverUrl = state.coverUrl, modifier = Modifier)
                
                Spacer(modifier = Modifier.width(16.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.title.ifEmpty { "..." },
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        text = state.artist.ifEmpty { "—" },
                        color = Color.Gray,
                        fontSize = 14.sp,
                        maxLines = 1
                    )
                }

                MediaControls(
                    isPlaying = state.isPlaying,
                    onPlayPause = { onIntent(DashboardIntent.PlayPause) },
                    onPrev = { onIntent(DashboardIntent.PrevTrack) },
                    onNext = { onIntent(DashboardIntent.NextTrack) }
                )
            }

            // Progress Bar
            Spacer(modifier = Modifier.height(16.dp))
            SmoothProgressBar(
                baseProgress = state.baseProgress,
                duration = state.duration,
                lastUpdateUnixTime = state.lastUpdateUnixTime,
                isPlaying = state.isPlaying
            )

            // Volume Control
            Spacer(modifier = Modifier.height(8.dp))
            VolumeControl(
                volume = state.volume,
                onVolumeChange = { onIntent(DashboardIntent.SetVolume(it)) }
            )
        }
    }
}

@Composable
fun MediaSourceSelector(sources: List<MediaSource>, selectedId: String?, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        sources.forEach { source ->
            val id = "${source.pluginId}:${source.deviceId}"
            val isSelected = id == selectedId
            Surface(
                onClick = { onSelect(id) },
                shape = MaterialTheme.shapes.small,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.height(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(
                        text = source.name.uppercase(),
                        color = if (isSelected) Color.White else Color.Gray,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun MediaCover(coverUrl: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var decodedModel by remember { mutableStateOf<Any?>(null) }
    var aspectRatio by remember { mutableFloatStateOf(1f) } // По умолчанию квадрат

    LaunchedEffect(coverUrl) {
        if (coverUrl.isEmpty()) {
            decodedModel = null
            aspectRatio = 1f
            return@LaunchedEffect
        }
        decodedModel = withContext(Dispatchers.Default) {
            if (coverUrl.startsWith("http")) coverUrl
            else {
                try {
                    val clean = if (coverUrl.contains(",")) coverUrl.substringAfter(",") else coverUrl
                    android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
                } catch (e: Exception) { null }
            }
        }
    }

    Box(
        modifier = modifier
            .height(80.dp) // Фиксированная высота
            .aspectRatio(aspectRatio) // Адаптивная ширина на основе пропорций
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.outline),
        contentAlignment = Alignment.Center
    ) {
        if (decodedModel != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(decodedModel)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onSuccess = { state ->
                    val drawable = state.result.drawable
                    if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                        aspectRatio = drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight.toFloat()
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.Gray)
        }
    }
}

@Composable
fun MediaControls(isPlaying: Boolean, onPlayPause: () -> Unit, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton(onClick = onPrev, modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)) {
            Icon(Icons.Default.SkipPrevious, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
        }
        Surface(
            onClick = onPlayPause,
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
        IconButton(onClick = onNext, modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)) {
            Icon(Icons.Default.SkipNext, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
fun SmoothProgressBar(baseProgress: Double, duration: Double, lastUpdateUnixTime: Double, isPlaying: Boolean) {
    val useCase: ObserveMediaProgressUseCase = koinInject()
    val progressRatio by useCase(baseProgress, duration, lastUpdateUnixTime, isPlaying)
        .collectAsState(initial = if (duration > 0) (baseProgress / duration).toFloat() else 0f)

    Column {
        Box(modifier = Modifier.fillMaxWidth().height(4.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.outline)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progressRatio)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(progressRatio.toDouble() * duration), color = Color.Gray, fontSize = 12.sp)
            Text(formatTime(duration), color = Color.Gray, fontSize = 12.sp)
        }
    }
}

@Composable
fun VolumeControl(volume: Int, onVolumeChange: (Int) -> Unit) {
    var localVolume by remember(volume) { mutableFloatStateOf(volume.toFloat()) }
    
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.VolumeDown, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(20.dp))
        Slider(
            value = localVolume,
            onValueChange = { localVolume = it },
            onValueChangeFinished = { onVolumeChange(localVolume.toInt()) },
            valueRange = 0f..100f,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.onSurface,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.outline
            ),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
        )
        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(20.dp))
    }
}

private fun formatTime(seconds: Double): String {
    if (seconds.isNaN()) return "00:00"
    val totalSeconds = seconds.toLong()
    val hours = totalSeconds / 3600
    val mins = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, mins, secs) else "%02d:%02d".format(mins, secs)
}
