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

import com.monithome.presentation.dashboard.util.t

@Composable
fun WidgetContent(widgetId: String, state: DashboardState, viewModel: DashboardViewModel) {
    when (widgetId) {
        "media" -> {
            if (state.activePlugins.any { it.type == "media_source" && it.active }) {
                MediaWidget(
                    state = state.mediaState, 
                    translations = state.translations,
                    onIntent = { viewModel.processIntent(it) }
                )
            }
        }
        "pc_system" -> {
            if (state.activePlugins.any { it.id == "pc_system" && it.active }) {
                SystemControlWidget(state = state, onIntent = { viewModel.processIntent(it) })
            }
        }
        "yandex_lyrics" -> {
            if (state.mediaState.isLyricsActive) {
                LyricsWidget(
                    title = state.t("yandex_lyrics_label", "Текст песни"),
                    translations = state.translations,
                    lyrics = state.lyrics,
                    baseProgressMs = (state.mediaState.baseProgress * 1000).toLong(),
                    lastUpdateUnixTime = state.mediaState.lastUpdateUnixTime,
                    isPlaying = state.mediaState.isPlaying,
                    coverUrl = state.mediaState.coverUrl,
                    onClick = { viewModel.processIntent(DashboardIntent.ToggleLyricsFullScreen) }
                )
            }
        }
        "system_stats", "pc_stats" -> {
            if (state.activePlugins.any { (it.id == "system_stats" || it.id == "pc_stats") && it.active }) {
                StatWidget(
                    title = state.t("plugin_name_pc_stats", "Статистика системы"), 
                    stats = state.stats[widgetId] ?: emptyMap(),
                    translations = state.translations,
                    onClick = { viewModel.processIntent(DashboardIntent.ToggleStatsExpanded) }
                )
            }
        }
        "pc_disks" -> {
            if (state.activePlugins.any { it.id == "pc_disks" && it.active }) {
                @Suppress("UNCHECKED_CAST")
                DisksWidget(
                    title = state.t("local_disk", "Жесткие диски"),
                    disks = (state.stats["pc_disks"]?.get("disks") as? List<Map<String, Any>>) ?: emptyList()
                )
            }
        }
        "app_launcher" -> {
            if (state.activePlugins.any { it.id == "app_launcher" && it.active }) {
                @Suppress("UNCHECKED_CAST")
                com.monithome.presentation.components.launcher.LauncherWidget(
                    title = state.t("plugin_name_app_launcher", "Запуск приложений"),
                    apps = (state.stats["app_launcher"]?.get("buttons") as? List<Map<String, Any>>) ?: emptyList(),
                    onIntent = { viewModel.processIntent(it) }
                )
            }
        }
    }
}



