package com.monithome.presentation.dashboard

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monithome.data.network.socket.PcSocketClient
import com.monithome.data.network.socket.PcDiscovery
import com.monithome.data.network.socket.SocketEvent
import com.monithome.domain.models.PluginInfo
import com.monithome.domain.repository.PluginRepository
import com.monithome.domain.repository.SettingsRepository
import com.monithome.presentation.dashboard.handlers.*
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

    private val manualSelectedSourceId = MutableStateFlow<String?>(null)

    // Хендлеры
    private val connectionHandler = ConnectionHandler(socketClient, pcDiscovery)
    private val statsHandler = StatsHandler(pluginRepository)
    private val mediaHandler = MediaHandler(pluginRepository)
    private val intentHandler = IntentHandler(socketClient, pluginRepository, settingsRepository, manualSelectedSourceId) { startDiscovery() }

    init {
        setupObservers()
        loadSavedSettings()
        startDiscovery()
    }

    private fun setupObservers() {
        // Статус соединения
        viewModelScope.launch {
            connectionHandler.observeConnectionState().collect { connState ->
                _state.update { connectionHandler.updateWithConnectionState(it, connState) }
            }
        }

        // Переводы и конфиги
        viewModelScope.launch {
            pluginRepository.translations.collect { trans ->
                if (trans.isNotEmpty()) _state.update { it.copy(translations = trans) }
            }
        }

        viewModelScope.launch {
            pluginRepository.uiConfigs.collect { configs ->
                handleUiConfigUpdate(configs)
                observeData(configs)
            }
        }

        // События сокета
        viewModelScope.launch {
            socketClient.events.collect { event ->
                handleSocketEvent(event)
            }
        }


    }

    private fun handleUiConfigUpdate(configs: List<PluginInfo>) {
        _state.update { s ->
            val isLyricsActive = configs.any { it.id == "yandex_lyrics" && it.active }
            val currentOrder = s.widgetOrder.toMutableList()
            configs.forEach { plugin ->
                val widgetId = when {
                    plugin.type == "media_source" -> "media"
                    plugin.id == "yandex_lyrics" -> "yandex_lyrics"
                    else -> plugin.id
                }
                if (!currentOrder.contains(widgetId)) currentOrder.add(widgetId)
            }
            s.copy(activePlugins = configs, widgetOrder = currentOrder, 
                mediaState = s.mediaState.copy(isLyricsActive = isLyricsActive), isLoading = false)
        }
    }

    private var statsJob: kotlinx.coroutines.Job? = null
    private var mediaJob: kotlinx.coroutines.Job? = null

    private fun observeData(configs: List<PluginInfo>) {
        statsJob?.cancel()
        mediaJob?.cancel()
        
        statsJob = viewModelScope.launch {
            statsHandler.observeStats(configs).collect { allStats ->
                _state.update { statsHandler.updateHistory(it, allStats) }
            }
        }

        mediaJob = viewModelScope.launch {
            mediaHandler.observeMedia(configs, manualSelectedSourceId).collect { (mediaState, lyrics) ->
                _state.update { it.copy(mediaState = mediaState, lyrics = lyrics) }
            }
        }
    }

    private fun handleSocketEvent(event: SocketEvent) {
        when (event) {
            is SocketEvent.AuthRequired -> _state.update { it.copy(isAuthRequired = true, isLoading = false) }
            is SocketEvent.AuthSuccess -> {
                settingsRepository.saveString("auth_token", event.token)
                _state.update { s ->
                    val ns = s.copy(isAuthRequired = false, isLoading = false, pcError = null)
                    event.themeColor?.let { settingsRepository.saveThemeColor(it); ns.copy(themeColor = it, serverSuggestedColor = it) } ?: ns
                }
            }
            is SocketEvent.UiConfig -> event.themeColor?.let { color ->
                settingsRepository.saveThemeColor(color)
                _state.update { it.copy(themeColor = color, serverSuggestedColor = color, isLoading = false) }
            }
            is SocketEvent.ThemeUpdate -> {
                settingsRepository.saveThemeColor(event.themeColor)
                _state.update { it.copy(themeColor = event.themeColor, serverSuggestedColor = event.themeColor) }
            }
            else -> {}
        }
    }

    private fun loadSavedSettings() {
        val savedOrder = settingsRepository.getWidgetOrder()
        val savedColor = settingsRepository.getThemeColor()
        val savedSource = settingsRepository.getString("selected_media_source")
        manualSelectedSourceId.value = savedSource
        _state.update { it.copy(widgetOrder = savedOrder ?: it.widgetOrder, themeColor = savedColor, 
            mediaState = it.mediaState.copy(selectedSourceId = savedSource)) }
    }

    private fun startDiscovery() {
        viewModelScope.launch {
            connectionHandler.observeDiscovery().collect { server ->
                _state.update { s ->
                    if (!s.discoveredServers.any { it.url == server.url }) s.copy(discoveredServers = s.discoveredServers + server) else s
                }
            }
        }
    }

    fun processIntent(intent: DashboardIntent) {
        intentHandler.handle(intent, _state)
    }
}
