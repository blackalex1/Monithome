package com.monithome.data.network.socket

import android.util.Log
import com.monithome.core.network.JsonParser
import com.monithome.core.network.MessagePackDecoder
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.engineio.client.transports.WebSocket
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.URISyntaxException

sealed class SocketConnectionState {
    object Disconnected : SocketConnectionState()
    object Connecting : SocketConnectionState()
    object Connected : SocketConnectionState()
    data class Error(val message: String) : SocketConnectionState()
}

sealed class SocketEvent {
    object AuthRequired : SocketEvent()
    data class AuthSuccess(val token: String, val encryptionKey: String, val themeColor: Long? = null) : SocketEvent()
    data class UiConfig(val config: JSONObject, val themeColor: Long? = null) : SocketEvent()
    data class ThemeUpdate(val themeColor: Long) : SocketEvent()
    data class ManagerDataObj(val data: JSONObject) : SocketEvent()
    data class ManagerDataArr(val data: JSONArray) : SocketEvent()
    data class StatsJson(val data: JSONObject) : SocketEvent()
    data class StatsBinary(val map: Map<String, Any>) : SocketEvent()
    data class YandexConfig(val data: JSONObject) : SocketEvent()
}

class PcSocketClient {
    private var socket: Socket? = null
    
    private val _connectionState = MutableStateFlow<SocketConnectionState>(SocketConnectionState.Disconnected)
    val connectionState: StateFlow<SocketConnectionState> = _connectionState.asStateFlow()

    // Используем SharedFlow для событий, чтобы репозитории могли на них подписаться
    private val _events = MutableSharedFlow<SocketEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SocketEvent> = _events.asSharedFlow()

    fun connect(url: String, token: String? = null) {
        if (_connectionState.value is SocketConnectionState.Connected || _connectionState.value is SocketConnectionState.Connecting) {
            return
        }

        var finalUrl = url.trim().replace(" ", "")
        if (finalUrl.isNotEmpty() && !finalUrl.startsWith("http")) {
            finalUrl = "http://$finalUrl"
        }
        
        // Проверка порта (игнорируя http://)
        val hasPort = if (finalUrl.startsWith("http")) {
            finalUrl.substringAfter("://").contains(":")
        } else {
            finalUrl.contains(":")
        }
        
        if (!hasPort && finalUrl.isNotEmpty()) {
            finalUrl += ":5000"
        }

        try {
            _connectionState.value = SocketConnectionState.Connecting
            val opts = IO.Options().apply {
                // Убираем принудительный WebSocket, даем Socket.IO выбрать лучший транспорт
                reconnection = true
                reconnectionDelay = 1000
                timeout = 15000 // Немного увеличим таймаут
                auth = if (token != null) {
                    mapOf("token" to token, "supports_encryption" to "true")
                } else {
                    mapOf("supports_encryption" to "true")
                }
            }

            socket = IO.socket(finalUrl, opts)
            setupListeners(token)
            socket?.connect()
        } catch (e: URISyntaxException) {
            _connectionState.value = SocketConnectionState.Error("Invalid URL: $url")
        }
    }

    private fun setupListeners(token: String?) {
        val s = socket ?: return

        s.on(Socket.EVENT_CONNECT) {
            Log.i("PcSocketClient", "Socket CONNECTED to server")
            _connectionState.value = SocketConnectionState.Connected
        }

        s.on(Socket.EVENT_DISCONNECT) {
            Log.w("PcSocketClient", "Socket DISCONNECTED from server")
            _connectionState.value = SocketConnectionState.Disconnected
        }

        s.on(Socket.EVENT_CONNECT_ERROR) { args ->
            val err = if (args != null && args.isNotEmpty()) args[0]?.toString() ?: "Unknown" else "Unknown"
            Log.e("PcSocketClient", "Connection ERROR: $err")
            _connectionState.value = SocketConnectionState.Error(err)
        }

        s.on("auth_required") {
            Log.i("PcSocketClient", "Server requested AUTH")
            _events.tryEmit(SocketEvent.AuthRequired)
        }

        s.on("auth_success") { args ->
            Log.i("PcSocketClient", "AUTH SUCCESSFUL")
            val rawData = args.getOrNull(0)
            if (rawData is JSONObject) {
                val t = rawData.optString("token", "")
                val key = rawData.optString("encryption_key", "")
                val colorStr = rawData.optString("theme_color", "")
                val color = parseHexColor(colorStr)
                _events.tryEmit(SocketEvent.AuthSuccess(t, key, color))
            } else if (rawData is Map<*, *>) {
                val t = rawData["token"]?.toString() ?: ""
                val key = rawData["encryption_key"]?.toString() ?: ""
                val colorStr = rawData["theme_color"]?.toString() ?: ""
                val color = parseHexColor(colorStr)
                _events.tryEmit(SocketEvent.AuthSuccess(t, key, color))
            }
        }

        s.on("authorized") {
            Log.i("PcSocketClient", "Received 'authorized' event")
        }

        s.on("ui_config") { args ->
            Log.i("PcSocketClient", "Event received: ui_config")
            (JsonParser.safeParseJson(args, "ui_config") as? JSONObject)?.let {
                val colorStr = it.optString("theme_color", "")
                Log.i("PcSocketClient", "UI CONFIG received: $it")
                val color = parseHexColor(colorStr)
                _events.tryEmit(SocketEvent.UiConfig(it, color))
            }
        }

        s.on("theme_update") { args ->
            Log.i("PcSocketClient", "Event received: theme_update")
            (JsonParser.safeParseJson(args, "theme_update") as? JSONObject)?.let {
                val colorStr = it.optString("theme_color", "")
                val color = parseHexColor(colorStr)
                Log.i("PcSocketClient", "THEME UPDATE received: '$colorStr', parsed: $color")
                if (color != null) {
                    _events.tryEmit(SocketEvent.ThemeUpdate(color))
                }
            }
        }

        s.on("manager_data") { args ->
            when (val data = JsonParser.safeParseJson(args, "manager_data")) {
                is JSONObject -> _events.tryEmit(SocketEvent.ManagerDataObj(data))
                is JSONArray -> _events.tryEmit(SocketEvent.ManagerDataArr(data))
            }
        }

        s.on("stats") { args ->
            val rawData = if (args.size > 1 && args[0] == "stats") args[1] else args[0]
            Log.v("PcSocketClient", "Received BINARY stats, size: ${if (rawData is ByteArray) rawData.size else "unknown"}")
            (rawData as? ByteArray)?.let { bytes ->
                try {
                    val map = MessagePackDecoder.decode(bytes)
                    _events.tryEmit(SocketEvent.StatsBinary(map))
                } catch (e: Exception) {
                    Log.e("PcSocketClient", "MessagePack decoding error", e)
                }
            }
        }

        s.on("stats_json") { args ->
            Log.v("PcSocketClient", "Received JSON stats")
            (JsonParser.safeParseJson(args, "stats_json") as? JSONObject)?.let {
                _events.tryEmit(SocketEvent.StatsJson(it))
            }
        }

        s.on("yandex_config") { args ->
            Log.i("PcSocketClient", "Event received: yandex_config")
            (JsonParser.safeParseJson(args, "yandex_config") as? JSONObject)?.let {
                val masked = JSONObject(it.toString())
                if (masked.has("yandex_token")) masked.put("yandex_token", "***")
                if (masked.has("devices")) {
                    val devices = masked.getJSONArray("devices")
                    for (i in 0 until devices.length()) {
                        val d = devices.getJSONObject(i)
                        if (d.has("glagol_token")) d.put("glagol_token", "***")
                    }
                }
                Log.i("PcSocketClient", "YANDEX CONFIG received (masked tokens): $masked")
                _events.tryEmit(SocketEvent.YandexConfig(it))
            } ?: Log.w("PcSocketClient", "Failed to parse yandex_config")
        }
    }

    fun authAttempt(password: String) {
        val payload = JSONObject().apply { put("code", password) }
        socket?.emit("auth_attempt", payload)
    }

    fun sendCommand(pluginId: String, action: String, target: String?, data: Any? = null) {
        val payload = JSONObject().apply {
            put("plugin_id", pluginId)
            put("action", action)
            put("target", target)
            if (data != null) {
                put("data", data)
            }
        }
        socket?.emit("plugin_command", payload)
    }

    fun disconnect() {
        socket?.disconnect()
        socket = null
        _connectionState.value = SocketConnectionState.Disconnected
    }

    private fun parseHexColor(hex: String?): Long? {
        if (hex.isNullOrEmpty()) return null
        return try {
            val sanitized = hex.replace("0x", "").replace("#", "")
            sanitized.toLong(16)
        } catch (e: Exception) {
            null
        }
    }
}
