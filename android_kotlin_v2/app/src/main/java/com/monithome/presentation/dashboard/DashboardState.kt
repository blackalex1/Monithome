package com.monithome.presentation.dashboard

import com.monithome.data.network.socket.DiscoveredServer
import com.monithome.domain.models.PluginInfo
import com.monithome.domain.models.CameraRequest

data class DashboardState(
    val isLoading: Boolean = false,
    val isReordering: Boolean = false,
    val themeColor: Long = 0xFF22C55E,
    val serverSuggestedColor: Long? = null,
    val isConnected: Boolean = false,
    val serverUrl: String = "",
    val pcError: String? = null,
    val mediaState: MediaUIState = MediaUIState.Empty,
    val activePlugins: List<PluginInfo> = emptyList(),
    val discoveredServers: List<DiscoveredServer> = emptyList(),
    val isAuthRequired: Boolean = false,
    val stats: Map<String, Map<String, Any>> = emptyMap(),
    val lyrics: List<com.monithome.domain.models.LyricLine> = emptyList(),
    val isLyricsFullScreen: Boolean = false,
    val translations: Map<String, String> = emptyMap(),
    val widgetOrder: List<String> = listOf("media", "pc_system", "virtual_camera", "yandex_lyrics", "system_stats", "pc_disks", "keenetic_mihomo"),
    
    // Stats History
    val isStatsExpanded: Boolean = false,
    val cpuHistory: List<Float> = emptyList(),
    val cpuTempHistory: List<Float> = emptyList(),
    val gpuLoadHistory: List<Float> = emptyList(),
    val gpuTempHistory: List<Float> = emptyList(),
    
    // Camera confirmation
    val showCameraConfirmDialog: Boolean = false,
    val pendingCameraRequest: CameraRequest.Start? = null
)

data class MediaUIState(
    val sources: List<MediaSource> = emptyList(),
    val selectedSourceId: String? = null,
    val title: String = "",
    val artist: String = "",
    val coverUrl: String = "",
    val isPlaying: Boolean = false,
    val baseProgress: Double = 0.0,
    val duration: Double = 0.0,
    val lastUpdateUnixTime: Double = 0.0,
    val volume: Int = 0,
    val isLyricsActive: Boolean = false
) {
    val currentProgress: Double
        get() {
            if (!isPlaying) return baseProgress
            val delta = (System.currentTimeMillis() / 1000.0) - lastUpdateUnixTime
            return (baseProgress + delta).coerceIn(0.0, duration)
        }

    companion object {
        val Empty = MediaUIState()
    }
}

data class MediaSource(
    val pluginId: String,
    val deviceId: String,
    val name: String
)

sealed class DashboardIntent {
    data class Connect(val url: String? = null) : DashboardIntent()
    object Disconnect : DashboardIntent()
    data class Auth(val token: String) : DashboardIntent()
    
    // Media
    data class SelectMediaSource(val id: String) : DashboardIntent()
    object PlayPause : DashboardIntent()
    object NextTrack : DashboardIntent()
    object PrevTrack : DashboardIntent()
    data class SetVolume(val volume: Int) : DashboardIntent()
    object ToggleLyricsFullScreen : DashboardIntent()
    object ToggleStatsExpanded : DashboardIntent()
    data class PluginCommand(
        val pluginId: String, 
        val action: String, 
        val target: String? = null, 
        val data: Map<String, Any> = emptyMap()
    ) : DashboardIntent()
    
    data class MoveWidget(val fromIndex: Int, val toIndex: Int) : DashboardIntent()
    data class ChangeThemeColor(val color: Long) : DashboardIntent()
    object StartReordering : DashboardIntent()
    object StopReordering : DashboardIntent()
    
    // Camera confirmation
    data class ConfirmCamera(val accept: Boolean) : DashboardIntent()
}
