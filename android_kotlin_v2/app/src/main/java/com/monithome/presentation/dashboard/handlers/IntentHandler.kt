package com.monithome.presentation.dashboard.handlers

import android.content.Context
import android.util.Log
import com.monithome.data.network.socket.PcSocketClient
import com.monithome.data.network.socket.CameraStreamService
import com.monithome.domain.repository.PluginRepository
import com.monithome.domain.repository.SettingsRepository
import com.monithome.presentation.dashboard.DashboardIntent
import com.monithome.presentation.dashboard.DashboardState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class IntentHandler(
    private val context: Context,
    private val socketClient: PcSocketClient,
    private val pluginRepository: PluginRepository,
    private val settingsRepository: SettingsRepository,
    private val manualSelectedSourceId: MutableStateFlow<String?>,
    private val startDiscovery: () -> Unit
) {
    fun handle(intent: DashboardIntent, state: MutableStateFlow<DashboardState>) {
        when (intent) {
            is DashboardIntent.Connect -> {
                if (intent.url != null) {
                    settingsRepository.saveString("server_url", intent.url)
                    
                    // Ищем UUID этого сервера среди обнаруженных
                    val discovered = state.value.discoveredServers.find { it.url == intent.url }
                    val uuid = discovered?.uuid
                    
                    val token = if (!uuid.isNullOrEmpty()) {
                        settingsRepository.getString("auth_token_$uuid")
                    } else {
                        settingsRepository.getString("auth_token") // Фолбэк на старый метод
                    }
                    
                    val deviceId = settingsRepository.getDeviceId()
                    
                    state.update { it.copy(serverUrl = intent.url) }
                    socketClient.connect(intent.url, token, deviceId)
                } else {
                    startDiscovery()
                }
            }
            is DashboardIntent.Disconnect -> socketClient.disconnect()
            is DashboardIntent.Auth -> {
                state.update { it.copy(isLoading = true) }
                socketClient.authAttempt(intent.token)
            }
            is DashboardIntent.SelectMediaSource -> {
                manualSelectedSourceId.value = intent.id
                settingsRepository.saveString("selected_media_source", intent.id)
                state.update { it.copy(mediaState = it.mediaState.copy(selectedSourceId = intent.id)) }
            }
            is DashboardIntent.PlayPause -> {
                val media = state.value.mediaState
                val source = media.sources.find { "${it.pluginId}:${it.deviceId}" == media.selectedSourceId }
                if (source != null) pluginRepository.sendCommand(source.pluginId, "play_pause", source.deviceId)
            }
            is DashboardIntent.NextTrack -> {
                val media = state.value.mediaState
                val source = media.sources.find { "${it.pluginId}:${it.deviceId}" == media.selectedSourceId }
                if (source != null) pluginRepository.sendCommand(source.pluginId, "next_track", source.deviceId)
            }
            is DashboardIntent.PrevTrack -> {
                val media = state.value.mediaState
                val source = media.sources.find { "${it.pluginId}:${it.deviceId}" == media.selectedSourceId }
                if (source != null) pluginRepository.sendCommand(source.pluginId, "prev_track", source.deviceId)
            }
            is DashboardIntent.SetVolume -> {
                val media = state.value.mediaState
                val source = media.sources.find { "${it.pluginId}:${it.deviceId}" == media.selectedSourceId }
                if (source != null) pluginRepository.sendCommand(source.pluginId, "set_volume:${intent.volume}", source.deviceId)
            }
            is DashboardIntent.Seek -> {
                val media = state.value.mediaState
                val source = media.sources.find { "${it.pluginId}:${it.deviceId}" == media.selectedSourceId }
                if (source != null) pluginRepository.sendCommand(source.pluginId, "seek:${intent.position}", source.deviceId)
            }
            is DashboardIntent.ToggleLyricsFullScreen -> state.update { it.copy(isLyricsFullScreen = !it.isLyricsFullScreen) }
            is DashboardIntent.PluginCommand -> {
                pluginRepository.sendCommand(intent.pluginId, intent.action, intent.target, intent.data)
            }
            is DashboardIntent.MoveWidget -> {
                val s = state.value
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
                        state.update { it.copy(widgetOrder = newList) }
                        settingsRepository.saveWidgetOrder(newList)
                    }
                }
            }
            is DashboardIntent.ChangeThemeColor -> {
                state.update { it.copy(themeColor = intent.color) }
                settingsRepository.saveThemeColor(intent.color)
            }
            DashboardIntent.StartReordering -> state.update { it.copy(isReordering = true) }
            DashboardIntent.StopReordering -> state.update { it.copy(isReordering = false) }
            DashboardIntent.ToggleStatsExpanded -> state.update { it.copy(isStatsExpanded = !it.isStatsExpanded) }
            is DashboardIntent.ConfirmCamera -> {
                val pending = state.value.pendingCameraRequest
                if (intent.accept && pending != null) {
                    try {
                        CameraStreamService.start(context, pending.useUsb, pending.useFront, pending.quality)
                    } catch (e: Exception) {
                        Log.e("IntentHandler", "Failed to start camera service", e)
                    }
                }
                state.update { it.copy(
                    showCameraConfirmDialog = false,
                    pendingCameraRequest = null
                ) }
            }
        }
    }
}
