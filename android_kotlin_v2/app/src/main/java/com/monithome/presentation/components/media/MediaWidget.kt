package com.monithome.presentation.components.media

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monithome.presentation.components.media.parts.*
import com.monithome.presentation.dashboard.DashboardIntent
import com.monithome.presentation.dashboard.MediaSource
import com.monithome.presentation.dashboard.MediaUIState

@Composable
fun MediaWidget(
    state: MediaUIState,
    translations: Map<String, String>,
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
            if (state.sources.size > 1) {
                MediaSourceSelector(
                    sources = state.sources,
                    translations = translations,
                    selectedId = state.selectedSourceId,
                    onSelect = { onIntent(DashboardIntent.SelectMediaSource(it)) }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                MediaCover(coverUrl = state.coverUrl)
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.title.ifEmpty { "..." },
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee()
                    )
                    Text(
                        text = state.artist.ifEmpty { "—" },
                        color = Color.Gray,
                        fontSize = 14.sp,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee()
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            SmoothProgressBar(
                baseProgress = state.baseProgress,
                duration = state.duration,
                lastUpdateUnixTime = state.lastUpdateUnixTime,
                isPlaying = state.isPlaying,
                onSeek = { position -> onIntent(DashboardIntent.Seek(position)) }
            )

            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                MediaControls(
                    isPlaying = state.isPlaying,
                    onPlayPause = { onIntent(DashboardIntent.PlayPause) },
                    onPrev = { onIntent(DashboardIntent.PrevTrack) },
                    onNext = { onIntent(DashboardIntent.NextTrack) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            VolumeControl(volume = state.volume, onVolumeChange = { onIntent(DashboardIntent.SetVolume(it)) })
        }
    }
}

@Composable
fun MediaSourceSelector(sources: List<MediaSource>, translations: Map<String, String>, selectedId: String?, onSelect: (String) -> Unit) {
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
                    val displayName = source.name.ifBlank { translations["plugin_name_${source.pluginId}"] ?: source.pluginId }
                    Text(text = displayName.uppercase(), color = if (isSelected) MaterialTheme.colorScheme.onPrimary else Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun VolumeControl(volume: Int, onVolumeChange: (Int) -> Unit) {
    var localVolume by remember(volume) { mutableFloatStateOf(volume.toFloat()) }
    var isDragging by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.AutoMirrored.Filled.VolumeDown, 
            contentDescription = null, 
            tint = Color.Gray, 
            modifier = Modifier.size(20.dp)
        )
        
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .height(18.dp)
                .padding(horizontal = 8.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        isDragging = true
                        localVolume = ((down.position.x / size.width) * 100f).coerceIn(0f, 100f)
                        
                        val pointerId = down.id
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId }
                            if (change == null || !change.pressed) {
                                break
                            }
                            change.consume()
                            localVolume = ((change.position.x / size.width) * 100f).coerceIn(0f, 100f)
                        }
                        
                        isDragging = false
                        onVolumeChange(localVolume.toInt())
                    }
                }
        ) {
            val width = constraints.maxWidth.toFloat()
            val height = constraints.maxHeight.toFloat()
            
            val primaryColor = MaterialTheme.colorScheme.primary
            val outlineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            
            val density = LocalDensity.current
            val thumbRadius = with(density) { (if (isDragging) 8.dp else 6.dp).toPx() }
            val trackHeight = with(density) { 4.dp.toPx() }
            
            Canvas(modifier = Modifier.fillMaxSize()) {
                val centerY = height / 2
                
                // 1. Draw inactive track (grey line)
                drawLine(
                    color = outlineColor,
                    start = androidx.compose.ui.geometry.Offset(0f, centerY),
                    end = androidx.compose.ui.geometry.Offset(width, centerY),
                    strokeWidth = trackHeight,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
                
                // 2. Draw active track (colored progress line)
                val activeRatio = localVolume / 100f
                val activeX = activeRatio * width
                if (activeX > 0f) {
                    drawLine(
                        color = primaryColor,
                        start = androidx.compose.ui.geometry.Offset(0f, centerY),
                        end = androidx.compose.ui.geometry.Offset(activeX, centerY),
                        strokeWidth = trackHeight,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                }
                
                // 3. Draw thumb (white circle with colored border outline)
                drawCircle(
                    color = Color.White,
                    radius = thumbRadius,
                    center = androidx.compose.ui.geometry.Offset(activeX, centerY)
                )
                drawCircle(
                    color = primaryColor,
                    radius = thumbRadius - 1.5f.dp.toPx(),
                    center = androidx.compose.ui.geometry.Offset(activeX, centerY),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 3.dp.toPx()
                    )
                )
            }
        }

        Icon(
            Icons.AutoMirrored.Filled.VolumeUp, 
            contentDescription = null, 
            tint = Color.Gray, 
            modifier = Modifier.size(20.dp)
        )
    }
}
