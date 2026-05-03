package com.monithome.data.repository_impl

import android.util.Log
import com.monithome.core.crypto.CryptoUtils
import com.monithome.data.network.socket.PcSocketClient
import com.monithome.data.network.socket.SocketEvent
import com.monithome.data.network.yandex.YandexStationClient
import com.monithome.data.network.yandex.YandexLyricsClient
import com.monithome.data.network.yandex.YandexStationEvent
import com.monithome.domain.models.PluginInfo
import com.monithome.domain.models.StationConfig
import com.monithome.domain.repository.PluginRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class PluginRepositoryImpl(
    private val pcSocketClient: PcSocketClient,
    private val yandexClient: YandexStationClient,
    private val yandexLyricsClient: YandexLyricsClient
) : PluginRepository {

    private val scope = CoroutineScope(Dispatchers.Default)

    private val _uiConfigs = MutableStateFlow<List<PluginInfo>>(emptyList())
    override val uiConfigs: StateFlow<List<PluginInfo>> = _uiConfigs.asStateFlow()

    private val _translations = MutableStateFlow<Map<String, String>>(emptyMap())
    override val translations: StateFlow<Map<String, String>> = _translations.asStateFlow()

    private val statsFlows = ConcurrentHashMap<String, MutableStateFlow<Map<String, Any>>>()
    
    private var encryptionKey: String? = null
    private var isYandexStandalone = false
    private var yandexToken: String? = null
    private var allowedDeviceIds = emptySet<String>()

    init {
        scope.launch {
            pcSocketClient.events.collect { event ->
                handlePcEvent(event)
            }
        }
        
        scope.launch {
            yandexClient.events.collect { event ->
                handleYandexEvent(event)
            }
        }
    }

    override fun getPluginStats(pluginId: String): StateFlow<Map<String, Any>> {
        return statsFlows.getOrPut(pluginId) { MutableStateFlow(emptyMap()) }.asStateFlow()
    }

    override fun isStandaloneMode(): Boolean = isYandexStandalone

    override fun sendCommand(pluginId: String, action: String, target: String?, data: Any?) {
        if (pluginId == "yandex_station" && target != null && isYandexStandalone) {
            val yandexCommand = when (action) {
                "play_pause" -> {
                    val currentStats = statsFlows["yandex_station"]?.value ?: emptyMap()
                    val devices = currentStats["devices"] as? List<Map<String, Any>>
                    val device = devices?.find { it["id"] == target }
                    val isPlaying = device?.get("playing") as? Boolean ?: false
                    if (isPlaying) "stop" else "play"
                }
                "next_track" -> "next"
                "prev_track" -> "prev"
                else -> if (action.startsWith("set_volume:")) "setVolume" else null
            }
            
            if (yandexCommand != null) {
                val payload = if (yandexCommand == "setVolume") {
                    val vol = action.substringAfter(":").toDouble() / 100.0
                    JSONObject().apply { put("volume", vol) }
                } else null
                
                yandexClient.sendCommand(target, yandexCommand, payload)
                return
            }
        }

        // Send to PC
        var payloadData = data
        val key = encryptionKey
        if (key != null && data != null && (pluginId == "yandex_station" || action.contains("token"))) {
            val encrypted = CryptoUtils.encrypt(data.toString(), key)
            if (encrypted != null) {
                payloadData = JSONObject().put("encrypted", encrypted)
            }
        }
        
        pcSocketClient.sendCommand(pluginId, action, target, payloadData)
    }

    private fun handlePcEvent(event: SocketEvent) {
        try {
            when (event) {
                is SocketEvent.AuthSuccess -> {
                    encryptionKey = event.encryptionKey
                    Log.i("PluginRepo", "AuthSuccess: Key set")
                }
                is SocketEvent.UiConfig -> {
                    parseUiConfig(event.config)
                }
                is SocketEvent.ManagerDataObj -> {
                    handleManagerData(event.data)
                }
                is SocketEvent.StatsJson -> {
                    val stats = event.data.optJSONObject("stats")
                    if (stats != null) bulkUpdate(jsonToMap(stats))
                }
                is SocketEvent.StatsBinary -> {
                    @Suppress("UNCHECKED_CAST")
                    val stats = event.map["stats"] as? Map<String, Any>
                    if (stats != null) bulkUpdate(stats)
                }
                is SocketEvent.YandexConfig -> {
                    handleYandexConfig(event.data)
                }
                else -> {}
            }
        } catch (e: Exception) {
            Log.e("PluginRepo", "Error handling PC event: ${e.message}", e)
        }
    }

    private fun handleYandexEvent(event: YandexStationEvent) {
        if (!isYandexStandalone) return
        when (event) {
            is YandexStationEvent.StateUpdated -> {
                processYandexState(event.deviceId, event.state)
            }
            is YandexStationEvent.ConnectionChanged -> {
                Log.i("PluginRepo", "Yandex device ${event.deviceId} connection status changed: ${event.isConnected}")
                // Update local stats to reflect online status
                val currentFlow = statsFlows.getOrPut("yandex_station") { MutableStateFlow(emptyMap()) }
                @Suppress("UNCHECKED_CAST")
                val devices = (currentFlow.value["devices"] as? List<Map<String, Any>>)?.toMutableList() ?: mutableListOf()
                val index = devices.indexOfFirst { it["id"] == event.deviceId }
                if (index >= 0) {
                    val updated = devices[index].toMutableMap()
                    updated["online"] = event.isConnected
                    updated["status"] = if (event.isConnected) "direct" else "connecting"
                    devices[index] = updated
                    currentFlow.value = currentFlow.value.toMutableMap().apply { put("devices", devices) }
                }
            }
            is YandexStationEvent.Error -> {
                Log.e("PluginRepo", "Yandex error on ${event.deviceId}: ${event.error}")
            }
        }
    }

    private fun bulkUpdate(updates: Map<String, Any>) {
        Log.v("PluginRepo", "bulkUpdate: ${updates.keys}")
        updates.forEach { (pluginId, pluginData) ->
            // Если мы в автономном режиме Яндекса, игнорируем статы Яндекса от ПК, 
            // чтобы они не затирали локальные данные от прямого подключения.
            if ((pluginId == "yandex_station" || pluginId == "yandex_lyrics") && isYandexStandalone) return@forEach

            if (pluginData is Map<*, *>) {
                val currentFlow = statsFlows.getOrPut(pluginId) { MutableStateFlow(emptyMap()) }
                @Suppress("UNCHECKED_CAST")
                val newData = (pluginData as Map<String, Any>).toMutableMap()
                newData["local_last_update"] = System.currentTimeMillis() / 1000.0
                currentFlow.value = mergeMaps(currentFlow.value, newData)
            }
        }
    }

    private fun parseUiConfig(config: JSONObject) {
        // Parse Translations
        val transObj = config.optJSONObject("translations")
        if (transObj != null) {
            val map = mutableMapOf<String, String>()
            val keys = transObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = transObj.optString(key)
            }
            _translations.value = map
        }

        val pluginsArr = config.optJSONArray("plugins") ?: return
        val list = mutableListOf<PluginInfo>()
        for (i in 0 until pluginsArr.length()) {
            val obj = pluginsArr.getJSONObject(i)
            list.add(
                PluginInfo(
                    id = obj.optString("id"),
                    name = obj.optString("name", "Unknown"),
                    description = obj.optString("description", ""),
                    type = obj.optString("type", "unknown"),
                    active = obj.optBoolean("active", false)
                )
            )
        }
        _uiConfigs.value = list
    }

    private fun handleYandexConfig(raw: JSONObject) {
        val masked = JSONObject(raw.toString())
        if (masked.has("yandex_token")) masked.put("yandex_token", "***")
        Log.i("PluginRepo", "handleYandexConfig: $masked")
        var data = raw
        if (data.has("encrypted") && encryptionKey != null) {
            val decrypted = CryptoUtils.decrypt(data.getString("encrypted"), encryptionKey!!)
            if (decrypted != null) {
                data = JSONObject(decrypted)
            }
        }

        val oldMode = isYandexStandalone
        isYandexStandalone = data.optBoolean("enabled", true)
        yandexToken = data.optString("yandex_token", null)
        
        Log.i("PluginRepository", "Yandex Config received. Standalone: $isYandexStandalone, Token present: ${!yandexToken.isNullOrEmpty()}")
        
        if (data.has("devices")) {
            val arr = data.getJSONArray("devices")
            val configs = mutableListOf<StationConfig>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("id")
                val ip = obj.optString("ip", "")
                val token = obj.optString("glagol_token", "")
                val port = obj.optInt("port", 1961)
                if (id.isNotEmpty() && token.isNotEmpty()) {
                    Log.i("PluginRepo", "Adding station: $id, IP: $ip, Token: ${token.take(5)}...")
                    configs.add(StationConfig(id, ip, token, obj.optString("name", "Яндекс Станция"), port))
                } else {
                    Log.w("PluginRepo", "Invalid station config: id=$id, token=${token.take(5)}...")
                }
            }
            allowedDeviceIds = configs.map { it.deviceId }.toSet()
            
            if (isYandexStandalone != oldMode) {
                Log.i("PluginRepository", "MODE SWITCH: ${if (oldMode) "Standalone -> PC" else "PC -> Standalone"}")
                // При смене режима очищаем старые данные, чтобы убрать "фантомные" устройства предыдущего режима
                statsFlows["yandex_station"]?.value = emptyMap()
                statsFlows["yandex_lyrics"]?.value = emptyMap()
            }
            if (isYandexStandalone) {
                Log.i("PluginRepo", "Starting Standalone mode with ${configs.size} devices")
                val initialDevices = configs.map { 
                    mapOf("id" to it.deviceId, "name" to it.name, "status" to "connecting", "title" to "Синхронизация...", "online" to false) 
                }
                statsFlows.getOrPut("yandex_station") { MutableStateFlow(emptyMap()) }.value = mapOf("devices" to initialDevices)
                yandexClient.updateConfigs(configs)
            } else {
                Log.i("PluginRepository", "Stopping Standalone mode (Switching to PC control)")
                yandexClient.stopAll()
            }
        }
    }

    private fun handleManagerData(data: JSONObject) {
        val map = jsonToMap(data)
        bulkUpdate(map)
    }

    private fun processYandexState(deviceId: String, state: JSONObject) {
        if (!allowedDeviceIds.contains(deviceId)) {
            Log.w("PluginRepository", "Ignored state for unknown device: $deviceId")
            return
        }
        Log.v("PluginRepository", "Processing state for $deviceId: ${state.optJSONObject("playerState")?.optString("title")}")

        val playerState = state.optJSONObject("playerState")
        val isPlaying = state.optBoolean("playing") || playerState?.optString("status") == "playing"
        val mappedData = mutableMapOf<String, Any>()
        mappedData["playing"] = isPlaying
        mappedData["volume"] = (state.optDouble("volume", 0.0) * 100).toInt()
        
        if (playerState != null) {
            val extra = playerState.optJSONObject("extra")
            mappedData["title"] = playerState.optString("title").ifEmpty { extra?.optString("title") ?: "" }
            mappedData["artist"] = playerState.optString("subtitle").ifEmpty { extra?.optString("artist") ?: "" }
            val trackId = playerState.optString("id").split(":").first()
            mappedData["track_id"] = trackId
            
            extra?.optString("coverURI")?.let { uri ->
                val cleanUri = uri.replace("%%", "400x400")
                mappedData["cover"] = if (cleanUri.startsWith("http")) cleanUri else "https://${cleanUri.removePrefix("//")}"
            }

            var progress = state.optDouble("progress", -1.0)
            if (progress < 0) progress = playerState.optDouble("progress", 0.0)
            
            var duration = state.optDouble("duration", playerState.optDouble("duration", 0.0))
            
            // Нормализация: если > 10000, то это миллисекунды
            if (progress > 10000) progress /= 1000.0
            if (duration > 10000) duration /= 1000.0

            mappedData["progress"] = progress
            mappedData["duration"] = duration
            mappedData["local_last_update"] = System.currentTimeMillis() / 1000.0
        }

        val currentFlow = statsFlows.getOrPut("yandex_station") { MutableStateFlow(emptyMap()) }
        val currentStats = currentFlow.value

        // Трэк-чекинг для лирики
        val trackId = mappedData["track_id"] as? String
        val oldTrackId = currentStats["track_id"] as? String ?: ""
        
        if (!trackId.isNullOrEmpty() && trackId != oldTrackId) {
            Log.i("PluginRepository", "TRACK CHANGE detected: $oldTrackId -> $trackId")
            // Сразу очищаем старый текст для этого устройства, чтобы он не висел при смене трека
            val lyricsFlow = statsFlows.getOrPut("yandex_lyrics") { MutableStateFlow(emptyMap()) }
            @Suppress("UNCHECKED_CAST")
            val currentLyricsDevices = (lyricsFlow.value["devices"] as? Map<String, Any>)?.toMutableMap() ?: mutableMapOf()
            if (currentLyricsDevices.containsKey(deviceId)) {
                currentLyricsDevices.remove(deviceId)
                lyricsFlow.value = mapOf("devices" to currentLyricsDevices)
            }

            if (isYandexStandalone && yandexToken != null) {
                scope.launch {
                    val lyrics = yandexLyricsClient.fetchLyrics(trackId, yandexToken!!)
                    if (lyrics.isNotEmpty()) {
                        Log.d("PluginRepository", "Lyrics found for track $trackId: ${lyrics.size} lines")
                        val lyricsFlow = statsFlows.getOrPut("yandex_lyrics") { MutableStateFlow(emptyMap()) }
                        @Suppress("UNCHECKED_CAST")
                        val lyricsDevices = (lyricsFlow.value["devices"] as? Map<String, Any>)?.toMutableMap() ?: mutableMapOf()
                        
                        lyricsDevices[deviceId] = mapOf(
                            "timings" to lyrics.map { line ->
                                mapOf("time" to line.timeMs, "text" to line.text)
                            },
                            "track_id" to trackId
                        )
                        
                        lyricsFlow.value = mapOf("devices" to lyricsDevices)
                    } else {
                        Log.w("PluginRepository", "Lyrics NOT found for track $trackId")
                    }
                }
            } else {
                // Если не автономно - просим ПК синхронизировать трек для лирики
                pcSocketClient.sendCommand("yandex_station", "sync_track", deviceId, JSONObject().put("track_id", trackId))
            }
        }

        val deviceUpdate: Map<String, Any> = mappedData.toMutableMap().apply {
            put("id", deviceId)
            put("status", "direct")
            put("track_id", trackId ?: "")
        }
        
        @Suppress("UNCHECKED_CAST")
        val devices = (currentStats["devices"] as? List<Map<String, Any>>)?.toMutableList() ?: mutableListOf()
        val index = devices.indexOfFirst { it["id"] == deviceId }
        if (index >= 0) {
            devices[index] = mergeMaps(devices[index], deviceUpdate)
        } else {
            devices.add(deviceUpdate)
        }
        
        currentFlow.value = currentStats.toMutableMap().apply {
            put("devices", devices)
            put("track_id", trackId ?: "")
        }
    }

    private fun jsonToMap(json: JSONObject): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.get(key)
            if (value is JSONObject) {
                map[key] = jsonToMap(value)
            } else if (value is JSONArray) {
                map[key] = jsonToList(value)
            } else {
                map[key] = value
            }
        }
        return map
    }

    private fun jsonToList(array: JSONArray): List<Any> {
        val list = mutableListOf<Any>()
        for (i in 0 until array.length()) {
            val value = array.get(i)
            if (value is JSONObject) {
                list.add(jsonToMap(value))
            } else if (value is JSONArray) {
                list.add(jsonToList(value))
            } else {
                list.add(value)
            }
        }
        return list
    }

    private fun mergeMaps(old: Map<String, Any>, new: Map<String, Any>): Map<String, Any> {
        val result = old.toMutableMap()
        new.forEach { (k, v) ->
            if (v is Map<*, *> && old[k] is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                result[k] = mergeMaps(old[k] as Map<String, Any>, v as Map<String, Any>)
            } else {
                result[k] = v
            }
        }
        return result
    }
}
