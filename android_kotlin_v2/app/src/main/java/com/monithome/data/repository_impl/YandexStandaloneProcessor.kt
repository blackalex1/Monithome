package com.monithome.data.repository_impl

import android.util.Log
import com.monithome.data.network.yandex.YandexLyricsClient
import com.monithome.data.network.yandex.YandexStationEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class YandexStandaloneProcessor(
    private val yandexLyricsClient: YandexLyricsClient
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val lastTrackIds = ConcurrentHashMap<String, String>()
    private val collabArtistsCache = ConcurrentHashMap<String, String>()

    fun handleYandexEvent(
        event: YandexStationEvent,
        isYandexStandalone: Boolean,
        yandexToken: String?,
        statsFlows: MutableMap<String, MutableStateFlow<Map<String, Any>>>
    ) {
        if (!isYandexStandalone) return
        when (event) {
            is YandexStationEvent.StateUpdated -> {
                processYandexState(event.deviceId, event.state, yandexToken, statsFlows)
            }
            is YandexStationEvent.ConnectionChanged -> {
                Log.i("YandexProcessor", "Device ${event.deviceId} connection status: ${event.isConnected}")
                val currentFlow = statsFlows.getOrPut("yandex_station") { MutableStateFlow(emptyMap()) }
                @Suppress("UNCHECKED_CAST")
                val devices = (currentFlow.value["devices"] as? List<Map<String, Any>>)?.toMutableList() ?: mutableListOf()
                val index = devices.indexOfFirst { it["id"] == event.deviceId }
                if (index >= 0) {
                    val updated = devices[index].toMutableMap()
                    updated["online"] = event.isConnected
                    updated["status"] = if (event.isConnected) "direct" else "connecting"
                    devices[index] = updated
                    currentFlow.update { it.toMutableMap().apply { put("devices", devices) } }
                }
            }
            is YandexStationEvent.Error -> {
                Log.e("YandexProcessor", "Error on ${event.deviceId}: ${event.error}")
            }
        }
    }

    private fun processYandexState(
        deviceId: String, 
        state: JSONObject,
        yandexToken: String?,
        statsFlows: MutableMap<String, MutableStateFlow<Map<String, Any>>>
    ) {
        val currentFlow = statsFlows.getOrPut("yandex_station") { MutableStateFlow(emptyMap()) }
        @Suppress("UNCHECKED_CAST")
        val devices = (currentFlow.value["devices"] as? List<Map<String, Any>>)?.toMutableList() ?: mutableListOf()
        val index = devices.indexOfFirst { it["id"] == deviceId }
        
        // ПАРСИНГ СОСТОЯНИЯ
        val playerState = state.optJSONObject("playerState") ?: JSONObject()
        val extra = playerState.optJSONObject("extra") ?: JSONObject()
        
        val title = playerState.optString("title").ifEmpty { extra.optString("title") }.ifEmpty { "" }
        val trackId = playerState.optString("id").ifEmpty { extra.optString("id") }.ifEmpty { "" }
        
        val rawArtist = playerState.optString("subtitle").ifEmpty { extra.optString("artist") }.ifEmpty { "" }
        val cachedCollab = if (trackId.isNotEmpty()) collabArtistsCache[trackId] else null
        val artist = cachedCollab ?: rawArtist
        
        // Проверка смены трека для загрузки текста
        if (trackId.isNotEmpty() && trackId != lastTrackIds[deviceId]) {
            lastTrackIds[deviceId] = trackId
            fetchLyricsForDevice(deviceId, trackId, artist, title, yandexToken, statsFlows)
            
            // Запускаем фоновое получение полного списка артистов для коллабораций
            if (yandexToken != null) {
                scope.launch {
                    try {
                        val artistsList = yandexLyricsClient.fetchTrackArtists(trackId, yandexToken)
                        if (artistsList.isNotEmpty()) {
                            val joinedArtists = artistsList.joinToString(", ")
                            collabArtistsCache[trackId] = joinedArtists
                            
                            // Обновляем состояние
                            val currentFlow = statsFlows.getOrPut("yandex_station") { MutableStateFlow(emptyMap()) }
                            @Suppress("UNCHECKED_CAST")
                            val devList = (currentFlow.value["devices"] as? List<Map<String, Any>>)?.toMutableList() ?: mutableListOf()
                            val idx = devList.indexOfFirst { it["id"] == deviceId }
                            if (idx >= 0) {
                                val updated = devList[idx].toMutableMap()
                                updated["artist"] = joinedArtists
                                devList[idx] = updated
                                currentFlow.update { it.toMutableMap().apply { put("devices", devList) } }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("YandexProcessor", "Failed to fetch artists for collab: ${e.message}")
                    }
                }
            }
        } else if (trackId.isEmpty() && title.isNotEmpty() && title != lastTrackIds[deviceId]) {
            // Фолбек: если нет trackId, но есть название
            lastTrackIds[deviceId] = title
            fetchLyricsForDevice(deviceId, null, artist, title, yandexToken, statsFlows)
        } else if (trackId.isEmpty() && title.isEmpty()) {
            lastTrackIds.remove(deviceId)
        }

        var cover = ""
        val coverRaw = extra.optString("coverURI")
        if (coverRaw.isNotEmpty()) {
            cover = "https://" + coverRaw.replace("%%", "400x400")
        }

        var progress = playerState.optDouble("progress", 0.0)
        var duration = playerState.optDouble("duration", 0.0)
        if (progress > 10000) progress /= 1000.0
        if (duration > 10000) duration /= 1000.0

        val isPlaying = state.optBoolean("playing", false) || 
                        playerState.optString("status") == "playing" ||
                        state.optString("playing") == "true"

        val volume = if (state.has("volume")) {
            (state.optDouble("volume", 0.0) * 100).toInt()
        } else {
            (currentFlow.value["devices"] as? List<Map<String, Any>>)?.find { it["id"] == deviceId }?.get("volume") as? Int ?: 0
        }

        val flattenedState = mutableMapOf<String, Any>(
            "id" to deviceId,
            "name" to ((currentFlow.value["devices"] as? List<Map<String, Any>>)?.find { it["id"] == deviceId }?.get("name") as? String ?: "Яндекс Станция"),
            "volume" to volume,
            "playing" to isPlaying,
            "title" to title,
            "artist" to artist,
            "cover" to cover,
            "track_id" to trackId,
            "progress" to progress,
            "duration" to duration,
            "online" to true,
            "status" to "direct",
            "last_update" to System.currentTimeMillis() / 1000.0,
            "local_last_update" to System.currentTimeMillis() / 1000.0,
            "alice_state" to state.optString("aliceState", "IDLE")
        )

        if (index >= 0) {
            val oldName = devices[index]["name"]
            if (oldName != null) flattenedState["name"] = oldName
            devices[index] = flattenedState
        } else {
            devices.add(flattenedState)
        }

        currentFlow.update { it.toMutableMap().apply { put("devices", devices) } }
    }

    private fun fetchLyricsForDevice(
        deviceId: String,
        trackId: String?,
        artist: String,
        title: String,
        token: String?,
        statsFlows: MutableMap<String, MutableStateFlow<Map<String, Any>>>
    ) {
        // Очищаем старые тексты сразу
        val lyricsFlow = statsFlows.getOrPut("yandex_lyrics") { MutableStateFlow(emptyMap()) }
        lyricsFlow.update { current ->
            val devices = (current["devices"] as? Map<String, Any>)?.toMutableMap() ?: mutableMapOf()
            devices[deviceId] = mapOf("timings" to emptyList<Map<String, Any>>())
            current.toMutableMap().apply { put("devices", devices) }
        }

        scope.launch {
            try {
                var lyrics = emptyList<com.monithome.domain.models.LyricLine>()
                
                // 1. Пытаемся по trackId если есть токен
                if (trackId != null && token != null) {
                    lyrics = yandexLyricsClient.fetchLyrics(trackId, token)
                }
                
                // 2. Если не вышло или нет данных - фолбек на поиск в LRCLIB (теперь с предварительным поиском в Яндексе)
                if (lyrics.isEmpty() && artist.isNotEmpty() && title.isNotEmpty()) {
                    lyrics = yandexLyricsClient.fetchLyricsBySearch(artist, title, token)
                }

                if (lyrics.isNotEmpty()) {
                    val lyricsFlow = statsFlows.getOrPut("yandex_lyrics") { MutableStateFlow(emptyMap()) }
                    val timings = lyrics.map { mapOf("time" to it.timeMs, "text" to it.text) }
                    
                    lyricsFlow.update { current ->
                        val devices = (current["devices"] as? Map<String, Any>)?.toMutableMap() ?: mutableMapOf()
                        devices[deviceId] = mapOf("timings" to timings)
                        current.toMutableMap().apply { put("devices", devices) }
                    }
                    Log.i("YandexProcessor", "Successfully updated lyrics for $deviceId (${artist} - ${title}). Lines: ${lyrics.size}")
                } else {
                    Log.w("YandexProcessor", "No lyrics found for: $artist - $title (Device: $deviceId)")
                }
            } catch (e: Exception) {
                Log.e("YandexProcessor", "Critical failure fetching lyrics for $deviceId: ${e.message}", e)
            }
        }
    }
}
