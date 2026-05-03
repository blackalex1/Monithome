package com.monithome.presentation.dashboard.components

import androidx.compose.runtime.Composable
import com.monithome.presentation.components.lyrics.LyricsWidget
import com.monithome.presentation.components.media.MediaWidget
import com.monithome.presentation.components.stats.StatWidget
import com.monithome.presentation.components.stats.DisksWidget
import com.monithome.presentation.components.system.SystemControlWidget
import com.monithome.presentation.dashboard.DashboardIntent
import com.monithome.presentation.dashboard.DashboardState
import com.monithome.presentation.dashboard.DashboardViewModel

@Composable
fun WidgetContent(widgetId: String, state: DashboardState, viewModel: DashboardViewModel) {
    when (widgetId) {
        "media" -> {
            if (state.activePlugins.any { it.type == "media_source" && it.active }) {
                MediaWidget(state = state.mediaState, onIntent = { viewModel.processIntent(it) })
            }
        }
        "pc_system" -> {
            if (state.activePlugins.any { it.id == "pc_system" && it.active }) {
                SystemControlWidget(onIntent = { viewModel.processIntent(it) })
            }
        }
        "yandex_lyrics" -> {
            if (state.mediaState.isLyricsActive) {
                LyricsWidget(
                    lyrics = state.lyrics,
                    currentTimeMs = (state.mediaState.currentProgress * 1000).toLong(),
                    coverUrl = state.mediaState.coverUrl,
                    onClick = { viewModel.processIntent(DashboardIntent.ToggleLyricsFullScreen) }
                )
            }
        }
        "system_stats" -> {
            if (state.activePlugins.any { it.id == "system_stats" && it.active }) {
                StatWidget(title = "Производительность", stats = state.stats["system_stats"] ?: emptyMap())
            }
        }
        "pc_disks" -> {
            if (state.activePlugins.any { it.id == "pc_disks" && it.active }) {
                @Suppress("UNCHECKED_CAST")
                DisksWidget(disks = (state.stats["pc_disks"]?.get("disks") as? List<Map<String, Any>>) ?: emptyList())
            }
        }
    }
}
