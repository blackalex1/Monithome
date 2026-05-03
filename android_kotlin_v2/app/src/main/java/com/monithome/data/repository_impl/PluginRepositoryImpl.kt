package com.monithome.data.repository_impl

import android.util.Log
import com.monithome.core.crypto.CryptoUtils
import com.monithome.data.network.socket.PcSocketClient
import com.monithome.data.network.socket.SocketEvent
import com.monithome.data.network.yandex.YandexStationClient
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
    private val yandexClient: YandexStationClient
) : PluginRepository {

    private val scope = CoroutineScope(Dispatchers.Default)

    private val _uiConfigs = MutableStateFlow<List<PluginInfo>>(emptyList())
    override val uiConfigs: StateFlow<List<PluginInfo>> = _uiConfigs.asStateFlow()

    private val statsFlows = ConcurrentHashMap<String, MutableStateFlow<Map<String, Any>>>()
    
    private var encryptionKey: String? = null
    private var isYandexStandalone = false

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

    override fun sendCommand(pluginId: String, action: String, target: String?, data: Any?) {
        if (pluginId == "yandex_station" && target != null && isYandexStandalone) {
            val yandexCommand = when (action) {
                "play_pause" -> "play_pause"
                "next_track" -> "next_track"
                "prev_track" -> "prev_track"
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
        when (event) {
            is SocketEvent.AuthSuccess -> {
                encryptionKey = event.encryptionKey
            }
            is SocketEvent.UiConfig -> {
                parseUiConfig(event.config)
            }
            is SocketEvent.ManagerDataObj -> {
                handleManagerData(event.data)
            }
            is SocketEvent.ManagerDataArr -> {
                // handle logic if array is used
            }
            is SocketEvent.StatsJson -> {
                val stats = event.data.optJSONObject("stats")
                if (stats != null) {
                    val map = jsonToMap(stats)
                    bulkUpdate(map)
                }
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
    }

    private fun handleYandexEvent(event: YandexStationEvent) {
        when (event) {
            is YandexStationEvent.StateUpdated -> {
                processYandexState(event.deviceId, event.state)
            }
            is YandexStationEvent.ConnectionChanged -> {
                Log.d("PluginRepo", "Yandex device ${event.deviceId} connected: ${event.isConnected}")
            }
            is YandexStationEvent.Error -> {
                Log.e("PluginRepo", "Yandex error on ${event.deviceId}: ${event.error}")
            }
        }
    }

    private fun bulkUpdate(updates: Map<String, Any>) {
        updates.forEach { (pluginId, pluginData) ->
            if (pluginData is Map<*, *>) {
                val currentFlow = statsFlows.getOrPut(pluginId) { MutableStateFlow(emptyMap()) }
                @Suppress("UNCHECKED_CAST")
                val newData = pluginData as Map<String, Any>
                currentFlow.value = mergeMaps(currentFlow.value, newData)
            }
        }
    }

    private fun parseUiConfig(config: JSONObject) {
        val pluginsArr = config.optJSONArray("plugins") ?: return
        val list = mutableListOf<PluginInfo>()
        for (i in 0 until pluginsArr.length()) {
            val obj = pluginsArr.getJSONObject(i)
            list.add(
                PluginInfo(
                    id = obj.optString("id"),
                    name = obj.optString("name", "Unknown"),
                    type = obj.optString("type", "unknown"),
                    active = obj.optBoolean("active", false)
                )
            )
        }
        _uiConfigs.value = list
    }

    private fun handleYandexConfig(raw: JSONObject) {
        var data = raw
        if (data.has("encrypted") && encryptionKey != null) {
            val decrypted = CryptoUtils.decrypt(data.getString("encrypted"), encryptionKey!!)
            if (decrypted != null) {
                data = JSONObject(decrypted)
            }
        }

        isYandexStandalone = data.optBoolean("enabled", true)
        
        if (data.has("devices")) {
            val arr = data.getJSONArray("devices")
            val configs = mutableListOf<StationConfig>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("id")
                val ip = obj.optString("ip", "")
                val token = obj.optString("glagol_token", "")
                if (id.isNotEmpty() && token.isNotEmpty()) {
                    configs.add(StationConfig(id, ip, token, obj.optString("name", "Яндекс Станция")))
                }
            }
            if (isYandexStandalone) {
                yandexClient.updateConfigs(configs)
            } else {
                yandexClient.stopAll()
            }
        }
    }

    private fun handleManagerData(data: JSONObject) {
        val map = jsonToMap(data)
        bulkUpdate(map)
    }

    private fun processYandexState(deviceId: String, state: JSONObject) {
        val mappedData = mutableMapOf<String, Any>()
        mappedData["playing"] = state.optBoolean("playing", false)
        mappedData["volume"] = (state.optDouble("volume", 0.0) * 100).toInt()
        
        val playerState = state.optJSONObject("playerState")
        if (playerState != null) {
            val extra = playerState.optJSONObject("extra")
            mappedData["title"] = playerState.optString("title").ifEmpty { extra?.optString("title") ?: "" }
            mappedData["artist"] = playerState.optString("subtitle").ifEmpty { extra?.optString("artist") ?: "" }
            mappedData["track_id"] = playerState.optString("id")
            
            extra?.optString("coverURI")?.let { uri ->
                val cleanUri = uri.replace("%%", "400x400")
                mappedData["cover"] = if (cleanUri.startsWith("http")) cleanUri else "https://${cleanUri.removePrefix("//")}"
            }

            var progress = state.optDouble("progress", -1.0)
            if (progress < 0) progress = playerState.optDouble("progress", 0.0)
            mappedData["progress"] = progress
            mappedData["duration"] = state.optDouble("duration", playerState.optDouble("duration", 0.0))
            mappedData["local_last_update"] = System.currentTimeMillis() / 1000.0
        }

        val deviceUpdate = mappedData + mapOf("id" to deviceId, "status" to "direct")
        
        val currentFlow = statsFlows.getOrPut("yandex_station") { MutableStateFlow(emptyMap()) }
        val currentStats = currentFlow.value
        @Suppress("UNCHECKED_CAST")
        val devices = (currentStats["devices"] as? List<Map<String, Any>>)?.toMutableList() ?: mutableListOf()
        
        val index = devices.indexOfFirst { it["id"] == deviceId }
        if (index >= 0) devices[index] = mergeMaps(devices[index], deviceUpdate) else devices.add(deviceUpdate)
        
        currentFlow.value = currentStats + mapOf("devices" to devices)
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
