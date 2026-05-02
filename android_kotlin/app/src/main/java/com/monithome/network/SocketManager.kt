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

    private var encryptionKey: String? = null

    fun setEncryptionKey(key: String?) {
        encryptionKey = key
        Log.i("SocketManager", "Encryption key updated: ${if (key != null) "SET" else "NULL"}")
    }

    fun getEncryptionKey(): String? = encryptionKey

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
            SocketDataHandler.reset() // Сбрасываем список зарегистрированных слушателей для нового сокета
            Log.i("SocketManager", "Connecting to: $finalUrl")
            
            val opts = IO.Options().apply {
                transports = arrayOf(WebSocket.NAME)
                reconnection = true
                reconnectionDelay = 1000
                timeout = 10000
                if (token != null) {
                    auth = mapOf(
                        "token" to token,
                        "supports_encryption" to "true"
                    )
                } else {
                    auth = mapOf("supports_encryption" to "true")
                }
            }
            
            socket = IO.socket(finalUrl, opts)
            
            socket?.off(Socket.EVENT_CONNECT)
            socket?.on(Socket.EVENT_CONNECT) {
                _isConnected.value = true
                _isConnecting.value = false
                _error.value = null
                val authObj = JSONObject().apply {
                    put("token", token)
                    put("supports_encryption", true)
                }
                socket?.emit("authorize", authObj)
            }

            socket?.off("authorized")
            socket?.on("authorized") {
                socket?.emit("get_yandex_config")
                // Запрашиваем полный конфиг сразу после авторизации
                socket?.emit("get_manager_data")
            }

            socket?.off(Socket.EVENT_DISCONNECT)
            socket?.on(Socket.EVENT_DISCONNECT) {
                _isConnected.value = false
                _isConnecting.value = false
                Log.w("SocketManager", "Disconnected from server")
            }

            socket?.off(Socket.EVENT_CONNECT_ERROR)
            socket?.on(Socket.EVENT_CONNECT_ERROR) { args ->
                _isConnecting.value = false
                val err = if (args != null && args.isNotEmpty()) args[0]?.toString() ?: "Unknown error" else "Unknown connection error"
                _error.value = err
                Log.e("SocketManager", "Connect error: $err")
            }

            socket?.off("auth_required")
            socket?.on("auth_required") {
                onAuthRequired?.invoke()
            }

            socket?.off("auth_success")
            socket?.on("auth_success") { args ->
                try {
                    val rawData = args.getOrNull(0)
                    Log.i("SocketManager", "RAW_AUTH_DATA: $rawData (Type: ${rawData?.javaClass?.name})")
                    
                    if (rawData is JSONObject || rawData is Map<*, *>) {
                        val token = if (rawData is JSONObject) rawData.optString("token", "") else (rawData as Map<*, *>)["token"]?.toString() ?: ""
                        val encKey = if (rawData is JSONObject) rawData.optString("encryption_key", "") else (rawData as Map<*, *>)["encryption_key"]?.toString() ?: ""
                        
                        if (encKey.isNotEmpty()) {
                            setEncryptionKey(encKey)
                            Log.i("SocketManager", "Encryption key updated: SET")
                        } else {
                            Log.w("SocketManager", "Auth success object received BUT encryption_key is EMPTY")
                        }
                        
                        onAuthSuccess?.invoke(token)
                        Log.i("SocketManager", "Auth success processed, emitting get_yandex_config")
                        socket?.emit("get_yandex_config")
                    } else {
                        Log.d("SocketManager", "Received simple auth success signal, waiting for data object...")
                    }
                } catch (e: Exception) {
                    Log.e("SocketManager", "AUTH_SUCCESS_PARSE_ERROR: ${e.message}")
                }
            }

            // Обработка manager_data
            socket?.off("manager_data")
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
            socket?.off("ui_config")
            socket?.on("ui_config") { args ->
                try {
                    val data = JsonParser.safeParseJson(args, "ui_config") as? JSONObject ?: return@on
                    val pluginsObj = data.opt("plugins") ?: return@on
                    val listType = object : com.google.gson.reflect.TypeToken<List<PluginInfo>>() {}.type
                    val configs: List<PluginInfo> = gson.fromJson(pluginsObj.toString(), listType)
                    PluginRepository.updateUiConfigs(configs)
                    SocketDataHandler.registerPluginListeners(configs)
                } catch (e: Exception) {
                    Log.e("SocketManager", "UI_CONFIG_ERROR: ${e.message}")
                }
            }

            // Обработка бинарных данных (MessagePack)
            socket?.off("stats")
            socket?.on("stats") { args ->
                val rawData = if (args.size > 1 && args[0] == "stats") args[1] else args[0]
                // android.util.Log.v("SocketManager", "Binary stats received, size: ${(rawData as? ByteArray)?.size ?: 0}")
                try {
                    val binaryData = rawData as? ByteArray ?: return@on
                    val statsMap = MessagePackDecoder.decode(binaryData)
                    if (statsMap.containsKey("stats")) {
                        @Suppress("UNCHECKED_CAST")
                        val actualStats = statsMap["stats"] as? Map<String, Any>
                        if (actualStats != null) {
                            // android.util.Log.v("SocketManager", "Stats keys: ${actualStats.keys}")
                            PluginRepository.bulkUpdate(actualStats)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SocketManager", "BINARY_STATS_ERROR: ${e.message}")
                }
            }

            // Обработка stats_json
            socket?.off("stats_json")
            socket?.on("stats_json") { args ->
                try {
                    val data = args.getOrNull(0) as? JSONObject ?: return@on
                    val statsJson = data.optJSONObject("stats")
                    if (statsJson != null) {
                        val statsMap = PluginRepository.jsonToMap(statsJson)
                        PluginRepository.bulkUpdate(statsMap)
                    }
                } catch (e: Exception) {
                    Log.e("SocketManager", "STATS_ERROR: ${e.message}")
                }
            }
            
            socket?.off("yandex_config")
            socket?.on("yandex_config") { args ->
                try {
                    var data = JsonParser.safeParseJson(args) as? JSONObject ?: return@on
                    
                    // Расшифровываем, если нужно
                    if (data.has("encrypted")) {
                        val encrypted = data.getString("encrypted")
                        val key = encryptionKey
                        if (key != null) {
                            val decrypted = CryptoUtils.decrypt(encrypted, key)
                            if (decrypted != null) {
                                data = JSONObject(decrypted)
                                Log.i("SocketManager", "Decrypted yandex_config successfully")
                            }
                        }
                    }
                    
                    handleYandexConfigEvent(data)
                } catch (e: Exception) {
                    Log.e("SocketManager", "YANDEX_CONFIG_ERROR: ${e.message}")
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
                    Log.w("SocketManager", "Skipping invalid device config: $deviceId (IP: $ip, Token: ${token.isNotEmpty()})")
                }
            }
            SocketDataHandler.updateStandalone(isEnabled)
            YandexStationManager.updateConfigs(configs, yandexToken, isEnabled)
        } else {
            Log.w("SocketManager", "yandex_config missing 'devices' field!")
        }
    }

    fun authAttempt(password: String) {
        val payload = JSONObject().apply {
            put("code", password)
        }
        socket?.emit("auth_attempt", payload)
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
            
            if (data != null) {
                // Шифруем данные команд для безопасности (по желанию можно ограничить список плагинов)
                val key = encryptionKey
                if (key != null && (pluginId == "yandex_station" || action.contains("token"))) {
                    val rawData = data.toString()
                    val encrypted = CryptoUtils.encrypt(rawData, key)
                    if (encrypted != null) {
                        put("data", JSONObject().put("encrypted", encrypted))
                        Log.i("SocketManager", "Encrypted command data for $pluginId")
                    } else {
                        put("data", data)
                    }
                } else {
                    put("data", data)
                }
            }
        }
        socket?.emit("plugin_command", payload)
    }
}
