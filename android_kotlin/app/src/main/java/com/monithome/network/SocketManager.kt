package com.monithome.network

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.monithome.data.PluginRepository
import com.monithome.models.PluginInfo
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI

object SocketManager {
    private var socket: Socket? = null
    private val gson = Gson()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting = _isConnecting.asStateFlow()

    fun clearError() { _error.value = null }

    fun connect(serverIp: String, authToken: String?) {
        _isConnecting.value = true
        _error.value = null
        try {
            val options = IO.Options().apply {
                auth = mapOf("token" to authToken)
                transports = arrayOf("polling", "websocket")
                reconnection = true
                reconnectionAttempts = Int.MAX_VALUE
                reconnectionDelay = 2000
            }
            
            socket = IO.socket(URI("http://$serverIp:5000"), options)
            
            socket?.on(Socket.EVENT_CONNECT) {
                android.util.Log.d("SocketManager", "Connected to $serverIp")
                _isConnecting.value = false
                _error.value = null
            }
            
            socket?.on(Socket.EVENT_CONNECT_ERROR) { args ->
                android.util.Log.e("SocketManager", "Connect error: ${args[0]}")
                _isConnecting.value = false
                _error.value = "Сервер не найден или не в сети"
            }

            // Обработка конфигурации плагинов (из manager_data)
            socket?.on("manager_data") { args ->
                try {
                    val data = when {
                        args[0] is JSONObject -> args[0] as JSONObject
                        args.size > 1 && args[1] is JSONObject -> args[1] as JSONObject
                        else -> JSONObject(args[0].toString())
                    }
                    if (data.has("all_plugins")) {
                        val pluginsObj = data.get("all_plugins")
                        val pluginsJson = pluginsObj.toString()
                        val listType = object : TypeToken<List<PluginInfo>>() {}.type
                        val plugins: List<PluginInfo> = gson.fromJson(pluginsJson, listType)
                        registerPluginListeners(plugins)
                        PluginRepository.updateUiConfigs(plugins)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SocketManager", "MANAGER_DATA_ERROR: ${e.message}")
                }
            }

            socket?.on("ui_config") { args ->
                try {
                    val data = when {
                        args[0] is JSONObject -> args[0] as JSONObject
                        args.size > 1 && args[1] is JSONObject -> args[1] as JSONObject
                        else -> JSONObject(args[0].toString())
                    }
                    if (data.has("config")) {
                        val pluginsObj = data.get("config")
                        val pluginsJson = pluginsObj.toString()
                        val listType = object : TypeToken<List<PluginInfo>>() {}.type
                        val plugins: List<PluginInfo> = gson.fromJson(pluginsJson, listType)
                        registerPluginListeners(plugins)
                        PluginRepository.updateUiConfigs(plugins)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SocketManager", "UI_CONFIG_ERROR: ${e.message}")
                }
            }

            // Обработка обновлений статистики (Бинарный MessagePack)
            socket?.on("stats") { args ->
                try {
                    val bytes = args[0] as ByteArray
                    val decoded = MessagePackDecoder.decode(bytes)
                    
                    // В серверном формате данные лежат в ключе "stats"
                    @Suppress("UNCHECKED_CAST")
                    val stats = decoded["stats"] as? Map<String, Any>
                    
                    if (stats != null) {
                        PluginRepository.bulkUpdate(stats)
                    }
                } catch (e: Exception) {
                    // System.out.println("SOCKET_ERROR_STATS: ${e.message}")
                }
            }

            // Динамические слушатели будут добавлены через registerPluginListeners

            // События авторизации
            socket?.on("auth_required") {
                android.util.Log.d("SocketManager", "Auth required, switching to pairing screen")
                onAuthRequired?.invoke()
            }

            socket?.on("auth_success") { args ->
                val data = args[0] as JSONObject
                val token = data.getString("token")
                android.util.Log.d("SocketManager", "Auth success, token received")
                _isConnecting.value = false
                _error.value = null
                onAuthSuccess?.invoke(token)
            }

            socket?.on("auth_failed") {
                android.util.Log.e("SocketManager", "Auth failed: Invalid code")
                _error.value = "Неверный код сопряжения"
                _isConnecting.value = false
            }

            socket?.connect()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    var onAuthRequired: (() -> Unit)? = null
    var onAuthSuccess: ((String) -> Unit)? = null

    private val registeredPlugins = mutableSetOf<String>()

    fun registerPluginListeners(plugins: List<PluginInfo>) {
        plugins.forEach { plugin ->
            val pId = plugin.id ?: ""
            if (pId.isNotEmpty() && !registeredPlugins.contains(pId)) {
                socket?.on("plugin_event:$pId") { args ->
                    try {
                        val data = args[0] as JSONObject
                        PluginRepository.handlePluginEvent(pId, data.getString("event"), data.get("data"))
                    } catch (e: Exception) {
                        // ignore
                    }
                }
                registeredPlugins.add(pId)
                System.out.println("SOCKET: Registered listener for plugin_event:$pId")
            }
        }
    }

    fun authAttempt(code: String) {
        _isConnecting.value = true
        _error.value = null
        android.util.Log.d("SocketManager", "Attempting auth with code: $code")
        val data = JSONObject().apply {
            put("code", code)
        }
        socket?.emit("auth_attempt", data)
    }

    fun sendCommand(pluginId: String, action: String, data: Any? = null, target: String = "all") {
        val payload = JSONObject().apply {
            put("plugin_id", pluginId)
            put("action", action)
            put("target", target)
            if (data != null) put("data", data)
        }
        socket?.emit("plugin_command", payload)
    }

    fun disconnect() {
        socket?.disconnect()
        socket = null
    }
}
