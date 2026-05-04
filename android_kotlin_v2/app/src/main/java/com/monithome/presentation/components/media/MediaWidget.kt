package com.monithome.presentation.components.media

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
                MediaControls(
                    isPlaying = state.isPlaying,
                    onPlayPause = { onIntent(DashboardIntent.PlayPause) },
                    onPrev = { onIntent(DashboardIntent.PrevTrack) },
                    onNext = { onIntent(DashboardIntent.NextTrack) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            SmoothProgressBar(state.baseProgress, state.duration, state.lastUpdateUnixTime, state.isPlaying)

            Spacer(modifier = Modifier.height(8.dp))
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.VolumeDown, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(20.dp))
        Slider(
            value = localVolume,
            onValueChange = { localVolume = it },
            onValueChangeFinished = { onVolumeChange(localVolume.toInt()) },
            valueRange = 0f..100f,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
        )
        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(20.dp))
    }
}
