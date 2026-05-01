package com.monithome.network

import com.monithome.data.PluginRepository
import android.util.Log
import com.monithome.models.PluginInfo
import com.google.gson.Gson
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.engineio.client.transports.WebSocket
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URISyntaxException

/**
 * Ядро Socket.IO клиента. Управляет соединением и маршрутизацией событий.
 */
object SocketManager {
    private var socket: Socket? = null
    private val gson = Gson()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting = _isConnecting.asStateFlow()

    // Колбэки для авторизации (используются в MainActivity)
    var onAuthRequired: (() -> Unit)? = null
    var onAuthSuccess: ((String) -> Unit)? = null
    var onDataReceived: (() -> Unit)? = null

    fun getSocket(): Socket? = socket

    fun clearError() {
        _error.value = null
    }

    fun connect(url: String, token: String? = null) {
        if (_isConnected.value || _isConnecting.value) return
        
        // Нормализация URL
        var finalUrl = url.trim()
        if (!finalUrl.startsWith("http://") && !finalUrl.startsWith("https://")) {
            finalUrl = "http://$finalUrl"
        }
        if (finalUrl.indexOf(":", 8) == -1) { // Если нет порта после http://
            finalUrl = "$finalUrl:5000"
        }

        try {
            _isConnecting.value = true
            android.util.Log.i("SocketManager", "Connecting to: $finalUrl")
            
            val opts = IO.Options().apply {
                transports = arrayOf(WebSocket.NAME)
                reconnection = true
                reconnectionDelay = 1000
                timeout = 10000
                if (token != null) {
                    auth = mapOf("token" to token)
                }
            }
            
            socket = IO.socket(finalUrl, opts)
            
            socket?.on(Socket.EVENT_CONNECT) {
                _isConnected.value = true
                _isConnecting.value = false
                _error.value = null
                val authObj = JSONObject().put("token", token)
                socket?.emit("authorize", authObj)
            }

            socket?.on("authorized") {
                socket?.emit("get_yandex_config")
                // Запрашиваем полный конфиг сразу после авторизации
                socket?.emit("get_manager_data")
            }

            socket?.on(Socket.EVENT_DISCONNECT) {
                _isConnected.value = false
                _isConnecting.value = false
                Log.w("SocketManager", "Disconnected from server")
            }

            socket?.on(Socket.EVENT_CONNECT_ERROR) { args ->
                _isConnecting.value = false
                val err = if (args != null && args.isNotEmpty()) args[0]?.toString() ?: "Unknown error" else "Unknown connection error"
                _error.value = err
                Log.e("SocketManager", "Connect error: $err")
            }

            // Обработка событий авторизации
            socket?.on("auth_required") {
                onAuthRequired?.invoke()
            }

            socket?.on("auth_success") { args ->
                try {
                    val data = args.getOrNull(0)
                    val token = if (data is JSONObject) {
                        data.optString("token", "")
                    } else if (data is Map<*, *>) {
                        data["token"]?.toString() ?: ""
                    } else {
                        data?.toString() ?: ""
                    }
                    onAuthSuccess?.invoke(token)
                    Log.i("SocketManager", "Auth success, emitting get_yandex_config")
                    socket?.emit("get_yandex_config", JSONObject())
                } catch (e: Exception) {
                    Log.e("SocketManager", "AUTH_SUCCESS_PARSE_ERROR: ${e.message}")
                }
            }

            // Обработка manager_data
            socket?.on("manager_data") { args ->
                try {
                    onDataReceived?.invoke()
                    val data = JsonParser.safeParseJson(args, "manager_data") ?: return@on
                    if (data is JSONObject) {
                        SocketDataHandler.handleManagerDataObject(data)
                    } else if (data is JSONArray) {
                        SocketDataHandler.handleManagerDataArray(data)
                    }
                } catch (e: Exception) {
                    Log.e("SocketManager", "MANAGER_DATA_ERROR: ${e.message}")
                }
            }

            // Обработка ui_config
            socket?.on("ui_config") { args ->
                try {
                    val data = JsonParser.safeParseJson(args, "ui_config") as? JSONObject ?: return@on
                    val pluginsObj = data.opt("plugins")
                    val listType = object : com.google.gson.reflect.TypeToken<List<PluginInfo>>() {}.type
                    val configs: List<PluginInfo> = gson.fromJson(pluginsObj.toString(), listType)
                    PluginRepository.updateUiConfigs(configs)
                    SocketDataHandler.registerPluginListeners(configs)
                } catch (e: Exception) {
                    android.util.Log.e("SocketManager", "UI_CONFIG_ERROR: ${e.message}")
                }
            }

            // Обработка бинарных данных (MessagePack)
            socket?.on("stats") { args ->
                val rawData = if (args.size > 1 && args[0] == "stats") args[1] else args[0]
                try {
                    val binaryData = rawData as? ByteArray ?: return@on
                    val statsMap = MessagePackDecoder.decode(binaryData)
                    if (statsMap.containsKey("stats")) {
                        @Suppress("UNCHECKED_CAST")
                        val actualStats = statsMap["stats"] as? Map<String, Any>
                        if (actualStats != null) {
                            PluginRepository.bulkUpdate(actualStats)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SocketManager", "BINARY_STATS_ERROR: ${e.message}")
                }
            }

            // Обработка stats_json
            socket?.on("stats_json") { args ->
                try {
                    val data = args.getOrNull(0) as? JSONObject ?: return@on
                    val statsJson = data.optJSONObject("stats")
                    if (statsJson != null) {
                        val statsMap = PluginRepository.jsonToMap(statsJson)
                        PluginRepository.bulkUpdate(statsMap)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SocketManager", "STATS_ERROR: ${e.message}")
                }
            }
            
            socket?.on("yandex_config") { args ->
                try {
                    val data = JsonParser.safeParseJson(args) as? JSONObject ?: return@on
                    handleYandexConfigEvent(data)
                } catch (e: Exception) {
                    android.util.Log.e("SocketManager", "YANDEX_CONFIG_ERROR: ${e.message}")
                }
            }

            socket?.connect()
        } catch (e: URISyntaxException) {
            _isConnecting.value = false
            _error.value = "Invalid URL: $url"
        }
    }

    fun handleYandexConfigEvent(data: JSONObject) {
        if (data.has("devices")) {
            val yandexToken = data.optString("yandex_token")
            val isEnabled = data.optBoolean("enabled", true)
            
            val devicesArray = data.getJSONArray("devices")
            val configs = mutableListOf<StationConfig>()
            val filter = SocketDataHandler.selectedYandexDevices
            
            for (i in 0 until devicesArray.length()) {
                val obj = devicesArray.getJSONObject(i)
                val deviceId = obj.optString("id")
                val ip = obj.optString("ip", "")
                val token = obj.optString("glagol_token", "")
                val name = obj.optString("name", "Яндекс Станция")
                
                if (deviceId.isNotEmpty() && ip.isNotEmpty() && token.isNotEmpty()) {
                    if (filter.isEmpty() || filter.contains(deviceId)) {
                        configs.add(StationConfig(
                            deviceId = deviceId,
                            ip = ip,
                            token = token,
                            name = name
                        ))
                    }
                } else {
                    android.util.Log.w("SocketManager", "Skipping invalid device config: $deviceId (IP: $ip, Token: ${token.isNotEmpty()})")
                }
            }
            SocketDataHandler.updateStandalone(isEnabled)
            YandexStationManager.updateConfigs(configs, yandexToken, isEnabled)
        } else {
            android.util.Log.w("SocketManager", "yandex_config missing 'devices' field!")
        }
    }

    fun authAttempt(password: String) {
        val payload = JSONObject().apply {
            put("code", password)
        }
        socket?.emit("auth_attempt", payload)
    }

    fun requestYandexConfig() {
        socket?.emit("get_yandex_config")
    }

    fun disconnect() {
        socket?.disconnect()
        _isConnected.value = false
        _isConnecting.value = false
    }

    fun sendCommand(pluginId: String, action: String, data: Any? = null, target: String? = null) {
        if (pluginId == "yandex_station" && target != null && SocketDataHandler.isYandexStandalone) {
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
                
                YandexStationManager.sendCommand(target, yandexCommand, payload)
            }
        }

        val payload = JSONObject().apply {
            put("plugin_id", pluginId)
            put("action", action)
            put("target", target)
            if (data != null) put("data", data)
        }
        socket?.emit("plugin_command", payload)
    }
}
