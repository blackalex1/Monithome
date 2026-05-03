package com.monithome.presentation.dashboard

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monithome.data.network.socket.PcSocketClient
import com.monithome.data.network.socket.PcDiscovery
import com.monithome.data.network.socket.DiscoveredServer
import com.monithome.data.network.socket.SocketEvent
import com.monithome.data.network.socket.SocketConnectionState
import com.monithome.domain.models.PluginInfo
import com.monithome.domain.repository.PluginRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class DashboardViewModel(
    private val socketClient: PcSocketClient,
    private val pcDiscovery: PcDiscovery,
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

        // Подписка на активные плагины UI
        viewModelScope.launch {
            pluginRepository.uiConfigs.collect { configs ->
                _state.update { s -> 
                val isLyricsActive = configs.any { it.id == "yandex_lyrics" && it.active }
                s.copy(
                    activePlugins = configs,
                    mediaState = s.mediaState.copy(isLyricsActive = isLyricsActive)
                )
                }
                
                // При изменении конфигов (или первом получении), запускаем мониторинг медиа
                observeMediaStats(configs)
            }
        }

        // Подписка на статус соединения
        // ...
        
        // Подписка на события сокета (авторизация)
        viewModelScope.launch {
            socketClient.events.collect { event ->
                if (event is SocketEvent.AuthRequired) {
                    _state.update { it.copy(isAuthRequired = true, isLoading = false) }
                }
                if (event is SocketEvent.AuthSuccess) {
                    _state.update { it.copy(isAuthRequired = false) }
                }
            }
        }
        
        // Автоматический поиск серверов
        startDiscovery()
    }

    private var statsJob: kotlinx.coroutines.Job? = null

    private fun observeMediaStats(configs: List<PluginInfo>) {
        statsJob?.cancel()
        val activeIds = configs.filter { it.active }.map { it.id }
        if (activeIds.isEmpty()) return

        statsJob = viewModelScope.launch {
            // Объединяем потоки статов от всех активных плагинов
            val flows = activeIds.map { id -> 
                pluginRepository.getPluginStats(id).map { id to it } 
            }
            
            combine(flows) { pairs ->
                val allStats = pairs.toMap()
                
                // Специальная обработка для медиа-плеера
                val mediaPlugins = configs.filter { it.type == "media_source" && it.active }.map { it.id }
                val allSources = mutableListOf<MediaSource>()
                var currentTitle = ""
                var currentArtist = ""
                var currentCover = ""
                var isPlaying = false
                var progress = 0.0
                var duration = 0.0
                var lastUpdate = 0.0
                var volume = 0

                mediaPlugins.forEach { pluginId ->
                    val stats = allStats[pluginId] ?: return@forEach
                    val devicesRaw = stats["devices"]
                    
                    val deviceList = when (devicesRaw) {
                        is List<*> -> devicesRaw.filterIsInstance<Map<String, Any>>()
                        is Map<*, *> -> devicesRaw.values.filterIsInstance<Map<String, Any>>()
                        else -> emptyList()
                    }

                    if (deviceList.isNotEmpty()) {
                        deviceList.forEach { dev ->
                            val devId = dev["id"] as? String ?: "default"
                            val name = dev["name"] as? String ?: pluginId
                            allSources.add(MediaSource(pluginId, devId, name))
                            
                            val fullId = "$pluginId:$devId"
                            val currentSelectedId = _state.value.mediaState.selectedSourceId
                            
                            if (fullId == currentSelectedId || currentSelectedId == null) {
                                if (currentSelectedId == null) {
                                    // Auto-select first available source
                                    _state.update { it.copy(mediaState = it.mediaState.copy(selectedSourceId = fullId)) }
                                }
                                if (fullId == _state.value.mediaState.selectedSourceId) {
                                    currentTitle = dev["title"] as? String ?: ""
                                    currentArtist = dev["artist"] as? String ?: ""
                                    currentCover = dev["cover"] as? String ?: ""
                                    isPlaying = dev["playing"] as? Boolean ?: false
                                    progress = (dev["progress"] as? Number)?.toDouble() ?: 0.0
                                    duration = (dev["duration"] as? Number)?.toDouble() ?: 0.0
                                    lastUpdate = (dev["local_last_update"] as? Number)?.toDouble()
                                        ?: (stats["local_last_update"] as? Number)?.toDouble()
                                        ?: 0.0
                                    volume = (dev["volume"] as? Number)?.toInt() ?: 0
                                }
                            }
                        }
                    } else if (stats.containsKey("title") || stats.containsKey("playing")) {
                        allSources.add(MediaSource(pluginId, "pc", configs.find { it.id == pluginId }?.name ?: pluginId))
                        val fullId = "$pluginId:pc"
                        if (fullId == _state.value.mediaState.selectedSourceId || _state.value.mediaState.selectedSourceId == null) {
                            if (_state.value.mediaState.selectedSourceId == null) {
                                _state.update { it.copy(mediaState = it.mediaState.copy(selectedSourceId = fullId)) }
                            }
                            if (fullId == _state.value.mediaState.selectedSourceId) {
                                currentTitle = stats["title"] as? String ?: ""
                                currentArtist = stats["artist"] as? String ?: ""
                                currentCover = stats["cover"] as? String ?: ""
                                isPlaying = stats["playing"] as? Boolean ?: false
                                progress = (stats["progress"] as? Number)?.toDouble() ?: 0.0
                                duration = (stats["duration"] as? Number)?.toDouble() ?: 0.0
                                lastUpdate = (stats["local_last_update"] as? Number)?.toDouble() ?: 0.0
                                volume = (stats["volume"] as? Number)?.toInt() ?: 0
                            }
                        }
                    }
                }
                
                // Проверка валидности выбранного источника
                val currentSelectedId = _state.value.mediaState.selectedSourceId
                val sourceStillExists = allSources.any { "${it.pluginId}:${it.deviceId}" == currentSelectedId }
                
                if (currentSelectedId != null && !sourceStillExists && allSources.isNotEmpty()) {
                    Log.i("DashboardVM", "Current source $currentSelectedId disappeared, resetting for auto-selection")
                    _state.update { it.copy(mediaState = it.mediaState.copy(selectedSourceId = null)) }
                }

                val isLyricsPluginActive = configs.any { it.id == "yandex_lyrics" && it.active }
                val mediaState = MediaUIState(
                    sources = allSources,
                    selectedSourceId = _state.value.mediaState.selectedSourceId,
                    title = currentTitle,
                    artist = currentArtist,
                    coverUrl = currentCover,
                    isPlaying = isPlaying,
                    baseProgress = progress,
                    duration = duration,
                    lastUpdateUnixTime = lastUpdate,
                    volume = volume,
                    isLyricsActive = isLyricsPluginActive && (currentSelectedId?.startsWith("yandex_station") == true)
                )
                
                if (mediaState.selectedSourceId != currentSelectedId) {
                    Log.d("DashboardVM", "Selected source changed to: ${mediaState.selectedSourceId}")
                }

                // Обработка лирики
                val lyricsStats = allStats["yandex_lyrics"]
                val lyricsDevices = lyricsStats?.get("devices") as? Map<String, Any>
                val selectedDeviceId = mediaState.selectedSourceId?.split(":")?.getOrNull(1)?.lowercase()?.trim()
                
                val currentDeviceLyrics = if (selectedDeviceId != null) {
                    // Ищем ключ более гибко (содержит ID или совпадает)
                    val key = lyricsDevices?.keys?.find { 
                        val k = it.lowercase().trim()
                        k == selectedDeviceId || k.contains(selectedDeviceId) || selectedDeviceId.contains(k)
                    }
                    if (key != null) lyricsDevices[key] as? Map<String, Any> else null
                } else null

                val lyricsRaw = currentDeviceLyrics?.get("timings") as? List<Any>
                val finalLyrics = lyricsRaw?.mapNotNull { item ->
                    val map = item as? Map<*, *> ?: return@mapNotNull null
                    val timeRaw = map["time"]
                    val timeMs = when (timeRaw) {
                        is Number -> timeRaw.toLong()
                        is String -> timeRaw.toLongOrNull() ?: 0L
                        else -> 0L
                    }
                    com.monithome.domain.models.LyricLine(
                        timeMs = timeMs,
                        text = map["text"] as? String ?: ""
                    )
                } ?: emptyList()
                
                // Если таймингов нет, но есть текст - добавим его как одну строку (fallback)
                val finalLyricsWithFallback = if (finalLyrics.isEmpty()) {
                    val fullText = currentDeviceLyrics?.get("lyrics") as? String
                    if (!fullText.isNullOrEmpty()) {
                        listOf(com.monithome.domain.models.LyricLine(0, fullText))
                    } else emptyList()
                } else finalLyrics

                allStats to (mediaState to finalLyricsWithFallback)
            }.collect { (allStats, pair) ->
                val (mediaState, parsedLyrics) = pair
                _state.update { it.copy(stats = allStats, mediaState = mediaState, lyrics = parsedLyrics) }
            }
        }
    }

    private fun startDiscovery() {
        viewModelScope.launch {
            pcDiscovery.discoverServers().collect { server ->
                _state.update { s ->
                    if (!s.discoveredServers.any { it.url == server.url }) {
                        val newList = s.discoveredServers + server
                        s.copy(discoveredServers = newList)
                    } else s
                }
            }
        }
    }

    fun processIntent(intent: DashboardIntent) {
        when (intent) {
            is DashboardIntent.Connect -> {
                if (intent.url != null) {
                    socketClient.connect(intent.url, null)
                } else {
                    startDiscovery()
                }
            }
            is DashboardIntent.Disconnect -> {
                socketClient.disconnect()
            }
            is DashboardIntent.Auth -> {
                _state.update { it.copy(isLoading = true) }
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
            is DashboardIntent.ToggleLyricsFullScreen -> {
                _state.update { it.copy(isLyricsFullScreen = !it.isLyricsFullScreen) }
            }
        }
    }
}
