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
import com.monithome.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class DashboardViewModel(
    private val socketClient: PcSocketClient,
    private val pcDiscovery: PcDiscovery,
    private val pluginRepository: PluginRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    // Поток для ручного выбора источника медиа
    private val manualSelectedSourceId = MutableStateFlow<String?>(null)

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

        // Подписка на переводы
        viewModelScope.launch {
            pluginRepository.translations.collect { trans ->
                if (trans.isNotEmpty()) {
                    _state.update { it.copy(translations = trans) }
                }
            }
        }

        // Подписка на активные плагины UI
        viewModelScope.launch {
            pluginRepository.uiConfigs
                .collect { configs ->
                _state.update { s ->
                    val isLyricsActive = configs.any { it.id == "yandex_lyrics" && it.active }
                    
                    // Обновляем общий порядок виджетов: добавляем новые, если их еще нет.
                    // Мы больше не удаляем отсюда неактивные плагины, чтобы они не пропадали навсегда.
                    val currentOrder = s.widgetOrder.toMutableList()
                    configs.forEach { plugin ->
                        val widgetId = when {
                            plugin.type == "media_source" -> "media"
                            plugin.id == "yandex_lyrics" -> "yandex_lyrics"
                            else -> plugin.id
                        }
                        // Добавляем только если это не скрытый системный плагин
                        if (!currentOrder.contains(widgetId)) {
                            currentOrder.add(widgetId)
                        }
                    }

                    Log.d("DashboardVM", "UiConfig processed. Active: ${configs.filter { it.active }.map { it.id }}")
                    s.copy(
                        activePlugins = configs,
                        widgetOrder = currentOrder,
                        mediaState = s.mediaState.copy(isLyricsActive = isLyricsActive),
                        isLoading = false
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
                    Log.d("DashboardVM", "AuthSuccess received. Saving token.")
                    settingsRepository.saveString("auth_token", event.token)
                    _state.update { s -> 
                        val newState = s.copy(isAuthRequired = false, isLoading = false, pcError = null)
                        event.themeColor?.let { color ->
                            settingsRepository.saveThemeColor(color)
                            newState.copy(themeColor = color, serverSuggestedColor = color)
                        } ?: newState
                    }
                }
                if (event is SocketEvent.UiConfig) {
                    Log.d("DashboardVM", "UiConfig received. Server color: ${event.themeColor}")
                    event.themeColor?.let { color ->
                        settingsRepository.saveThemeColor(color)
                        _state.update { it.copy(themeColor = color, serverSuggestedColor = color, isLoading = false) }
                    } ?: _state.update { it.copy(isLoading = false) }
                }
                if (event is SocketEvent.ThemeUpdate) {
                    Log.d("DashboardVM", "ThemeUpdate received: ${event.themeColor}")
                    settingsRepository.saveThemeColor(event.themeColor)
                    _state.update { it.copy(themeColor = event.themeColor, serverSuggestedColor = event.themeColor) }
                }
            }
        }
        
        // Загрузка сохраненного порядка виджетов и цвета темы
        val savedOrder = settingsRepository.getWidgetOrder()
        val savedColor = settingsRepository.getThemeColor()
        val savedSource = settingsRepository.getString("selected_media_source")
        
        manualSelectedSourceId.value = savedSource

        _state.update { it.copy(
            widgetOrder = savedOrder ?: it.widgetOrder,
            themeColor = savedColor,
            mediaState = it.mediaState.copy(selectedSourceId = savedSource)
        ) }
        
        // Автоматический поиск серверов
        startDiscovery()
    }

    private var statsJob: kotlinx.coroutines.Job? = null
    private var mediaJob: kotlinx.coroutines.Job? = null

    private fun observeMediaStats(configs: List<PluginInfo>) {
        statsJob?.cancel()
        mediaJob?.cancel()
        val activeIds = configs.filter { it.active }.map { it.id }
        if (activeIds.isEmpty()) {
            Log.d("DashboardVM", "No active plugins to monitor")
            return
        }

        statsJob = viewModelScope.launch {
            val flows = activeIds.map { id -> 
                pluginRepository.getPluginStats(id).map { id to it } 
            }
            
            Log.d("DashboardVM", "Starting statsJob for ${activeIds.size} plugins: $activeIds")
            
            combine(flows) { pairs ->
                val allStats = pairs.toMap()
                Log.v("DashboardVM", "Stats update: ${allStats.keys}")
                allStats
            }.collect { allStats ->
                _state.update { s ->
                    val systemStats = allStats["system_stats"]
                    val newCpuHistory = if (systemStats != null) {
                        val cpu = (systemStats["cpu"] as? Number)?.toFloat() ?: 0f
                        (s.cpuHistory + cpu).takeLast(50)
                    } else s.cpuHistory

                    val newCpuTempHistory = if (systemStats != null) {
                        val temp = (systemStats["cpu_temp"] as? Number)?.toFloat() ?: 0f
                        (s.cpuTempHistory + temp).takeLast(50)
                    } else s.cpuTempHistory

                    val newGpuLoadHistory = if (systemStats != null) {
                        val load = (systemStats["gpu_load"] as? Number)?.toFloat() ?: 0f
                        (s.gpuLoadHistory + load).takeLast(50)
                    } else s.gpuLoadHistory

                    val newGpuTempHistory = if (systemStats != null) {
                        val temp = (systemStats["gpu_temp"] as? Number)?.toFloat() ?: 0f
                        (s.gpuTempHistory + temp).takeLast(50)
                    } else s.gpuTempHistory

                    s.copy(
                        stats = allStats,
                        cpuHistory = newCpuHistory,
                        cpuTempHistory = newCpuTempHistory,
                        gpuLoadHistory = newGpuLoadHistory,
                        gpuTempHistory = newGpuTempHistory
                    )
                }
            }
        }

        // Отдельно следим за медиа и лирикой
        mediaJob = viewModelScope.launch {
            val mediaPlugins = configs.filter { it.type == "media_source" && it.active }
            if (mediaPlugins.isEmpty()) {
                _state.update { it.copy(mediaState = MediaUIState.Empty) }
                return@launch
            }

            val statsFlows = mediaPlugins.map { plugin ->
                pluginRepository.getPluginStats(plugin.id).map { plugin to it }
            }

            // Объединяем все статы медиа-плагинов И текущий ручной выбор
            val mediaDataFlow = combine(combine(statsFlows) { it.toList() }, manualSelectedSourceId) { pairs, selectedId ->
                val allSources = mutableListOf<MediaSource>()
                pairs.forEach { (plugin, stats) ->
                    val devices = stats["devices"] as? List<Map<String, Any>>
                    if (!devices.isNullOrEmpty()) {
                        devices.forEach { dev ->
                            val devId = dev["id"] as? String ?: "default"
                            val devName = dev["name"] as? String ?: plugin.name
                            allSources.add(MediaSource(plugin.id, devId, devName))
                        }
                    } else {
                        val devId = (stats["device_id"] as? String) ?: "pc"
                        allSources.add(MediaSource(plugin.id, devId, plugin.name))
                    }
                }

                // Ищем данные для выбранного устройства
                val currentPair = pairs.find { (plugin, stats) ->
                    val devices = stats["devices"] as? List<Map<String, Any>>
                    if (!devices.isNullOrEmpty()) {
                        devices.any { "${plugin.id}:${it["id"]}" == selectedId }
                    } else {
                        "${plugin.id}:${stats["device_id"] ?: "pc"}" == selectedId
                    }
                }

                val targetPair = currentPair ?: if (selectedId == null) pairs.firstOrNull() else null
                
                if (targetPair != null) {
                    val (plugin, stats) = targetPair
                    val devices = stats["devices"] as? List<Map<String, Any>>
                    val deviceStats = if (!devices.isNullOrEmpty()) {
                        devices.find { "${plugin.id}:${it["id"]}" == selectedId } ?: devices.first()
                    } else stats

                    MediaUIState(
                        sources = allSources,
                        selectedSourceId = selectedId ?: "${plugin.id}:${deviceStats["id"] ?: deviceStats["device_id"] ?: "pc"}",
                        title = (deviceStats["title"] as? String) ?: "",
                        artist = (deviceStats["artist"] as? String) ?: (deviceStats["subtitle"] as? String) ?: "",
                        coverUrl = (deviceStats["cover"] as? String) ?: "",
                        isPlaying = (deviceStats["playing"] as? Boolean) ?: false,
                        baseProgress = (deviceStats["progress"] as? Number)?.toDouble() ?: 0.0,
                        duration = (deviceStats["duration"] as? Number)?.toDouble() ?: 0.0,
                        lastUpdateUnixTime = (deviceStats["last_update"] as? Number)?.toDouble() 
                            ?: (deviceStats["local_last_update"] as? Number)?.toDouble() 
                            ?: (System.currentTimeMillis() / 1000.0),
                        volume = (deviceStats["volume"] as? Number)?.toInt() ?: 0,
                        isLyricsActive = configs.any { it.id == "yandex_lyrics" && it.active }
                    )
                } else {
                    MediaUIState.Empty.copy(
                        sources = allSources, 
                        selectedSourceId = selectedId,
                        isLyricsActive = configs.any { it.id == "yandex_lyrics" && it.active }
                    )
                }
            }

            val lyricsFlow = pluginRepository.getPluginStats("yandex_lyrics").map { stats ->
                val devices = stats["devices"] as? Map<String, Any>
                val selectedId = manualSelectedSourceId.value
                val actualDeviceId = selectedId?.substringAfter(":") ?: ""
                val data = devices?.get(actualDeviceId) as? Map<String, Any>
                
                (data?.get("timings") as? List<Map<String, Any>>)?.map {
                    com.monithome.domain.models.LyricLine(
                        timeMs = (it["time"] as? Number)?.toLong() ?: 0L,
                        text = (it["text"] as? String) ?: ""
                    )
                } ?: emptyList()
            }

            combine(mediaDataFlow, lyricsFlow) { mediaState, lyrics ->
                mediaState to lyrics
            }.collect { (mediaState, lyrics) ->
                _state.update { it.copy(mediaState = mediaState, lyrics = lyrics) }
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
                    val savedToken = settingsRepository.getString("auth_token")
                    socketClient.connect(intent.url, savedToken)
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
                manualSelectedSourceId.value = intent.id
                settingsRepository.saveString("selected_media_source", intent.id)
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
            is DashboardIntent.PluginCommand -> {
                pluginRepository.sendCommand(intent.pluginId, intent.action, intent.target, intent.data)
            }
            is DashboardIntent.MoveWidget -> {
                val s = _state.value
                // Получаем список видимых ID по той же логике, что и на экране
                val visibleWidgets = s.widgetOrder.filter { id ->
                    when (id) {
                        "media" -> s.activePlugins.any { it.type == "media_source" && it.active }
                        "pc_system" -> s.activePlugins.any { it.id == "pc_system" && it.active }
                        "yandex_lyrics" -> s.mediaState.isLyricsActive
                        "system_stats" -> s.activePlugins.any { it.id == "system_stats" && it.active }
                        "pc_disks" -> s.activePlugins.any { it.id == "pc_disks" && it.active }
                        else -> s.activePlugins.any { it.id == id && it.active }
                    }
                }

                if (intent.fromIndex in visibleWidgets.indices && intent.toIndex in visibleWidgets.indices) {
                    val fromId = visibleWidgets[intent.fromIndex]
                    val toId = visibleWidgets[intent.toIndex]

                    val newList = s.widgetOrder.toMutableList()
                    val fromFullIdx = newList.indexOf(fromId)
                    val toFullIdx = newList.indexOf(toId)

                    if (fromFullIdx != -1 && toFullIdx != -1) {
                        val item = newList.removeAt(fromFullIdx)
                        newList.add(toFullIdx, item)
                        _state.update { it.copy(widgetOrder = newList) }
                        settingsRepository.saveWidgetOrder(newList)
                    }
                }
            }
            is DashboardIntent.ChangeThemeColor -> {
                _state.update { it.copy(themeColor = intent.color) }
                settingsRepository.saveThemeColor(intent.color)
            }
            DashboardIntent.StartReordering -> {
                _state.update { it.copy(isReordering = true) }
            }
            DashboardIntent.StopReordering -> {
                _state.update { it.copy(isReordering = false) }
            }
            DashboardIntent.ToggleStatsExpanded -> {
                _state.update { it.copy(isStatsExpanded = !it.isStatsExpanded) }
            }
        }
    }
}
