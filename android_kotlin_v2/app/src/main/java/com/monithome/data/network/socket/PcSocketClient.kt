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
    data class AuthSuccess(val token: String, val encryptionKey: String) : SocketEvent()
    data class UiConfig(val config: JSONObject) : SocketEvent()
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

        var finalUrl = url.trim()
        if (!finalUrl.startsWith("http://") && !finalUrl.startsWith("https://")) {
            finalUrl = "http://$finalUrl"
        }
        if (finalUrl.indexOf(":", 8) == -1) {
            finalUrl = "$finalUrl:5000"
        }

        try {
            _connectionState.value = SocketConnectionState.Connecting
            val opts = IO.Options().apply {
                transports = arrayOf(WebSocket.NAME)
                reconnection = true
                reconnectionDelay = 1000
                timeout = 10000
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
            val authObj = JSONObject().apply {
                put("token", token)
                put("supports_encryption", true)
            }
            s.emit("authorize", authObj)
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
            Log.d("PcSocketClient", "Server requested AUTH")
            _events.tryEmit(SocketEvent.AuthRequired)
        }

        s.on("auth_success") { args ->
            Log.i("PcSocketClient", "AUTH SUCCESSFUL")
            val rawData = args.getOrNull(0)
            if (rawData is JSONObject) {
                val t = rawData.optString("token", "")
                val key = rawData.optString("encryption_key", "")
                _events.tryEmit(SocketEvent.AuthSuccess(t, key))
                s.emit("get_yandex_config")
            } else if (rawData is Map<*, *>) {
                val t = rawData["token"]?.toString() ?: ""
                val key = rawData["encryption_key"]?.toString() ?: ""
                _events.tryEmit(SocketEvent.AuthSuccess(t, key))
                s.emit("get_yandex_config")
            }
        }

        s.on("authorized") {
            Log.d("PcSocketClient", "Received 'authorized' event")
            s.emit("get_yandex_config")
            s.emit("get_manager_data")
        }

        s.on("ui_config") { args ->
            (JsonParser.safeParseJson(args, "ui_config") as? JSONObject)?.let {
                _events.tryEmit(SocketEvent.UiConfig(it))
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
            (args.getOrNull(0) as? JSONObject)?.let {
                _events.tryEmit(SocketEvent.StatsJson(it))
            }
        }

        s.on("yandex_config") { args ->
            (JsonParser.safeParseJson(args) as? JSONObject)?.let {
                _events.tryEmit(SocketEvent.YandexConfig(it))
            }
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
}
