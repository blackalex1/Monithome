package com.monithome.network

import android.content.Context
import android.net.nsd.NsdManager
import android.util.Log
import com.monithome.data.PluginRepository
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Менеджер для прямого управления Яндекс Станциями с планшета.
 * Реализует протокол Glagol через WebSockets.
 */
object YandexStationManager {
    private const val TAG = "YandexStationManager"
    
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .hostnameVerifier { _, _ -> true }
        .sslSocketFactory(
            YandexSslUtils.createSSLSocketFactory(),
            YandexSslUtils.trustManager
        )
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeConnections = ConcurrentHashMap<String, WebSocket>()
    private val stationConfigs = ConcurrentHashMap<String, StationConfig>()
    
    private var yandexToken: String? = null
    private var nsdManager: NsdManager? = null
    private val lastTrackIds = ConcurrentHashMap<String, String>()
    
    private lateinit var lyricsFetcher: YandexLyricsFetcher
    private var discovery: YandexDiscovery? = null
    private var lastModeState: Boolean? = null

    fun init(context: Context) {
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        lyricsFetcher = YandexLyricsFetcher(client, scope)
        discovery = YandexDiscovery(nsdManager!!) { deviceId, ip ->
            stationConfigs[deviceId]?.let { config ->
                config.ip = ip
                reconnect(deviceId)
            }
        }
    }

    /**
     * Обновить конфигурацию станций
     */
    fun updateConfigs(configs: List<StationConfig>, token: String? = null, enabled: Boolean = true) {
        if (lastModeState != enabled) {
            Log.i(TAG, ">>> DIRECT CONTROL MODE: ${if (enabled) "ON" else "OFF"} <<<")
            lastModeState = enabled
        }
        
        if (!enabled) {
            stopAll()
            PluginRepository.clearDirectStatus("yandex_station")
            return
        }
        
        yandexToken = token
        
        val newIds = configs.map { it.deviceId }.toSet()
        PluginRepository.setYandexFilter(newIds)
        
        // 1. Удаляем неактуальные
        stationConfigs.keys().toList().forEach { id ->
            if (!newIds.contains(id)) {
                activeConnections[id]?.close(1000, "Removed from config")
                activeConnections.remove(id)
                stationConfigs.remove(id)
            }
        }

        // 2. Добавляем или обновляем
        configs.forEach { config ->
            val old = stationConfigs[config.deviceId]
            val hasConnection = activeConnections.containsKey(config.deviceId)
            if (old == null || old.token != config.token || old.ip != config.ip || !hasConnection) {
                stationConfigs[config.deviceId] = config
                reconnect(config.deviceId)
            }
        }
        
        // Синхронизация с UI репозиторием: принудительно ставим имена из конфига
        val devices = configs.map { config ->
            mapOf(
                "id" to config.deviceId,
                "name" to (config.name ?: "Яндекс Станция"),
                "status" to "direct",
                "online" to false // Пока не подключились
            )
        }
        PluginRepository.updateStats("yandex_station", mapOf("devices" to devices))
    }

    fun stopAll() {
        activeConnections.values.forEach { it.close(1000, "Stopped") }
        activeConnections.clear()
        stationConfigs.clear()
        lastTrackIds.clear()
        yandexToken = null
    }

    private fun reconnect(deviceId: String) {
        activeConnections[deviceId]?.close(1000, "Reconnecting")
        val config = stationConfigs[deviceId] ?: return
        
        val ip = config.ip
        if (ip == null) {
            discovery?.discover(config)
            return
        }

        Log.d(TAG, "Connecting to ${config.name} at wss://$ip:${config.port}")
        val request = Request.Builder().url("wss://$ip:${config.port}").build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                activeConnections[deviceId] = webSocket
                sendAuthPing(webSocket, config.token, deviceId)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(deviceId, text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "CONNECTION FAILURE for ${config.name}: ${t.message}")
                activeConnections.remove(deviceId)
                scope.launch {
                    delay(10000)
                    if (stationConfigs.containsKey(deviceId)) reconnect(deviceId)
                }
            }
        }

        client.newWebSocket(request, listener)
    }

    private fun sendAuthPing(ws: WebSocket, token: String, deviceId: String) {
        val payload = JSONObject().apply {
            put("conversationToken", token)
            put("id", UUID.randomUUID().toString())
            put("sentTime", System.currentTimeMillis())
            put("payload", JSONObject().apply { put("command", "ping") })
        }
        ws.send(payload.toString())
        
        scope.launch {
            delay(500)
            sendCommand(deviceId, "getState")
            while (activeConnections[deviceId] == ws) {
                delay(3000)
                if (activeConnections[deviceId] == ws) sendCommand(deviceId, "getState")
            }
        }
    }

    fun sendCommand(deviceId: String, command: String, commandPayload: JSONObject? = null) {
        val config = stationConfigs[deviceId] ?: return
        val ws = activeConnections[deviceId] ?: return

        var finalCommand = command
        val finalPayload = commandPayload ?: JSONObject()

        when (command) {
            "play_pause" -> {
                val currentStats = PluginRepository.getPluginStats("yandex_station").value
                @Suppress("UNCHECKED_CAST")
                val devices = currentStats["devices"] as? List<Map<String, Any>>
                val isPlaying = devices?.find { it["id"] == deviceId }?.get("playing") as? Boolean ?: false
                finalCommand = if (isPlaying) "stop" else "play"
            }
            "next_track" -> finalCommand = "next"
            "prev_track" -> finalCommand = "prev"
            "setVolume" -> finalCommand = "setVolume"
        }

        val payload = JSONObject().apply {
            put("conversationToken", config.token)
            put("id", UUID.randomUUID().toString())
            put("sentTime", System.currentTimeMillis())
            put("payload", JSONObject().apply { 
                put("command", finalCommand)
                val keys = finalPayload.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, finalPayload.get(key))
                }
            })
        }
        ws.send(payload.toString())
    }

    private fun handleMessage(deviceId: String, text: String) {
        try {
            val json = JSONObject(text)
            if (json.has("state")) processState(deviceId, json.getJSONObject("state"))
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message from $deviceId: ${e.message}")
        }
    }

    private fun processState(deviceId: String, state: JSONObject) {
        val config = stationConfigs[deviceId] ?: return
        val mappedData = mutableMapOf<String, Any>()
        
        mappedData["playing"] = state.optBoolean("playing", false)
        mappedData["volume"] = (state.optDouble("volume", 0.0) * 100).toInt()
        
        val playerState = state.optJSONObject("playerState")
        if (playerState != null) {
            val extra = playerState.optJSONObject("extra")
            val trackId = playerState.optString("id")
            
            mappedData["title"] = playerState.optString("title").ifEmpty { extra?.optString("title") ?: "" }
            mappedData["artist"] = playerState.optString("subtitle").ifEmpty { extra?.optString("artist") ?: "" }
            mappedData["track_id"] = trackId
            
            extra?.optString("coverURI")?.let { uri ->
                val cleanUri = uri.replace("%%", "400x400")
                mappedData["cover"] = if (cleanUri.startsWith("http")) cleanUri else "https://${cleanUri.removePrefix("//")}"
            }

            var progress = state.optDouble("progress", -1.0)
            if (progress < 0) progress = playerState.optDouble("progress", 0.0)
            mappedData["progress"] = progress
            mappedData["duration"] = state.optDouble("duration", playerState.optDouble("duration", 0.0))
            
            // Проверка смены трека для текстов (используем локальный кэш для надежности)
            val oldTrackId = lastTrackIds[deviceId]
            
            if (trackId.isNotEmpty() && trackId != oldTrackId) {
                lastTrackIds[deviceId] = trackId
                Log.d(TAG, "Track changed for $deviceId: $oldTrackId -> $trackId. Fetching lyrics...")
                lyricsFetcher.fetch(deviceId, trackId, yandexToken)
                SocketManager.sendCommand("yandex_station", "sync_track", mapOf("track_id" to trackId), target = deviceId)
            }
        }

        // Обновление репозитория
        val deviceUpdate = mappedData + mapOf("id" to deviceId, "name" to config.name, "status" to "direct")
        val currentStats = PluginRepository.getPluginStats("yandex_station").value
        @Suppress("UNCHECKED_CAST")
        val devices = (currentStats["devices"] as? List<Map<String, Any>>)?.toMutableList() ?: mutableListOf()
        
        val index = devices.indexOfFirst { it["id"] == deviceId }
        if (index >= 0) devices[index] = devices[index] + deviceUpdate else devices.add(deviceUpdate)
        
        PluginRepository.updateStats("yandex_station", mapOf("devices" to devices))
    }
}
