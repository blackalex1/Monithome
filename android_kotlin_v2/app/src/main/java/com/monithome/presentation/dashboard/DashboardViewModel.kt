package com.monithome.presentation.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monithome.data.network.socket.PcSocketClient
import com.monithome.data.network.socket.SocketConnectionState
import com.monithome.domain.repository.PluginRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class DashboardViewModel(
    private val socketClient: PcSocketClient,
    private val pluginRepository: PluginRepository
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    init {
        // Подписка на статус соединения
        viewModelScope.launch {
            socketClient.connectionState.collect { connState ->
                _state.update {
                    it.copy(
                        isConnected = connState is SocketConnectionState.Connected,
                        pcError = (connState as? SocketConnectionState.Error)?.message,
                        isLoading = connState is SocketConnectionState.Connecting
                    )
                }
            }
        }

        // Подписка на активные плагины UI (для определения, включен ли Lyrics)
        viewModelScope.launch {
            pluginRepository.uiConfigs.collect { configs ->
                val activeMediaConfigs = configs.filter { it.type == "media_source" && it.active }
                _state.update { s -> 
                    val isLyricsActive = configs.any { it.id == "yandex_lyrics" && it.active }
                    s.copy(
                        activePlugins = configs,
                        mediaState = s.mediaState.copy(isLyricsActive = isLyricsActive)
                    )
                }
            }
        }

        // В будущем тут будет collectStatsFlow для каждого медиа-устройства
    }

    fun processIntent(intent: DashboardIntent) {
        when (intent) {
            is DashboardIntent.Connect -> {
                // TODO: Получить URL и токен из DataStore
                socketClient.connect("http://192.168.1.100:5000", null)
            }
            is DashboardIntent.Disconnect -> {
                socketClient.disconnect()
            }
            is DashboardIntent.Auth -> {
                socketClient.authAttempt(intent.token)
            }
            is DashboardIntent.SelectMediaSource -> {
                _state.update { it.copy(mediaState = it.mediaState.copy(selectedSourceId = intent.id)) }
            }
            is DashboardIntent.PlayPause -> {
                val media = _state.value.mediaState
                val source = media.sources.find { "${it.pluginId}:${it.deviceId}" == media.selectedSourceId }
                if (source != null) {
                    pluginRepository.sendCommand(source.pluginId, "play_pause", source.deviceId)
                }
            }
            is DashboardIntent.NextTrack -> {
                val media = _state.value.mediaState
                val source = media.sources.find { "${it.pluginId}:${it.deviceId}" == media.selectedSourceId }
                if (source != null) {
                    pluginRepository.sendCommand(source.pluginId, "next_track", source.deviceId)
                }
            }
            is DashboardIntent.PrevTrack -> {
                val media = _state.value.mediaState
                val source = media.sources.find { "${it.pluginId}:${it.deviceId}" == media.selectedSourceId }
                if (source != null) {
                    pluginRepository.sendCommand(source.pluginId, "prev_track", source.deviceId)
                }
            }
            is DashboardIntent.SetVolume -> {
                val media = _state.value.mediaState
                val source = media.sources.find { "${it.pluginId}:${it.deviceId}" == media.selectedSourceId }
                if (source != null) {
                    pluginRepository.sendCommand(source.pluginId, "set_volume:${intent.volume}", source.deviceId)
                }
            }
        }
    }
}
