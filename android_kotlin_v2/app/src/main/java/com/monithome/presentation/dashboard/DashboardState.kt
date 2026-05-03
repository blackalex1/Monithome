package com.monithome.presentation.dashboard

import com.monithome.domain.models.PluginInfo

data class DashboardState(
    val isLoading: Boolean = true,
    val isConnected: Boolean = false,
    val pcError: String? = null,
    val mediaState: MediaUIState = MediaUIState.Empty,
    val activePlugins: List<PluginInfo> = emptyList()
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
    object Connect : DashboardIntent()
    object Disconnect : DashboardIntent()
    data class Auth(val token: String) : DashboardIntent()
    
    // Media
    data class SelectMediaSource(val id: String) : DashboardIntent()
    object PlayPause : DashboardIntent()
    object NextTrack : DashboardIntent()
    object PrevTrack : DashboardIntent()
    data class SetVolume(val volume: Int) : DashboardIntent()
}
