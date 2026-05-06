package com.monithome.data.network.socket

import android.util.Log
import com.monithome.core.crypto.CryptoUtils
import com.monithome.core.network.JsonParser
import com.monithome.core.network.MessagePackDecoder
import io.socket.client.IO
import io.socket.client.Socket
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
    data class AuthRequired(val serverUuid: String? = null) : SocketEvent()
    data class AuthSuccess(val token: String, val encryptionKey: String, val themeColor: Long? = null, val serverUuid: String? = null) : SocketEvent()
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
    @Volatile
    private var encryptionKey: String? = null
    
    private val _connectionState = MutableStateFlow<SocketConnectionState>(SocketConnectionState.Disconnected)
    val connectionState: StateFlow<SocketConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<SocketEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SocketEvent> = _events.asSharedFlow()

    fun connect(url: String, token: String? = null, deviceId: String? = null) {
        if (_connectionState.value is SocketConnectionState.Connected || _connectionState.value is SocketConnectionState.Connecting) {
            Log.d("PcSocketClient", "Connect called but already connecting/connected. Skipping.")
            return
        }
        
        var finalUrl = if (url.startsWith("http")) url else "http://$url"
        Log.i("PcSocketClient", "Connecting to $finalUrl (token present: ${token != null})")
        
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
                reconnection = true
                reconnectionDelay = 1000
                timeout = 15000
                auth = mutableMapOf<String, String>().apply {
                    put("supports_encryption", "true")
                    if (token != null) put("token", token)
                    if (deviceId != null) put("device_id", deviceId)
                }
            }

            socket = IO.socket(finalUrl, opts)
            setupListeners()
            socket?.connect()
        } catch (e: URISyntaxException) {
            _connectionState.value = SocketConnectionState.Error("Invalid URL: $url")
        }
    }

    private fun setupListeners() {
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

        onData(s, "auth_required") { data ->
            val obj = when(data) {
                is JSONObject -> data
                is Map<*, *> -> JSONObject(data)
                is String -> try { JSONObject(data) } catch(e: Exception) { null }
                else -> null
            }
            val uuid = obj?.optString("server_uuid")
            Log.i("PcSocketClient", "Server requested AUTH. Server UUID: $uuid")
            _events.tryEmit(SocketEvent.AuthRequired(uuid))
        }

        onData(s, "auth_success") { data ->
            Log.i("PcSocketClient", "AUTH SUCCESSFUL. Type: ${data.javaClass.simpleName}")
            try {
                val obj = when(data) {
                    is JSONObject -> data
                    is Map<*, *> -> JSONObject(data)
                    is String -> JSONObject(data)
                    else -> null
                }
                
                obj?.let {
                    val t = it.optString("token", "")
                    val key = it.optString("encryption_key", "")
                    val uuid = it.optString("server_uuid", "")
                    encryptionKey = key
                    Log.i("PcSocketClient", "AuthSuccess: Key set, Server UUID: $uuid")
                    val colorStr = it.optString("theme_color", "")
                    val color = parseHexColor(colorStr)
                    _events.tryEmit(SocketEvent.AuthSuccess(t, key, color, uuid))
                }
            } catch (e: Exception) {
                Log.e("PcSocketClient", "Auth parsing error", e)
            }
        }

        onData(s, "authorized") {
            Log.i("PcSocketClient", "Received 'authorized' event")
        }

        onData(s, "ui_config") { data ->
            Log.i("PcSocketClient", "UI CONFIG received. Type: ${data.javaClass.simpleName}")
            val obj = when(data) {
                is JSONObject -> data
                is String -> try { JSONObject(data) } catch(e: Exception) { null }
                else -> null
            }
            obj?.let {
                val colorStr = it.optString("theme_color", "")
                val color = parseHexColor(colorStr)
                _events.tryEmit(SocketEvent.UiConfig(it, color))
            }
        }

        onData(s, "theme_update") { data ->
            val obj = when(data) {
                is JSONObject -> data
                is String -> try { JSONObject(data) } catch(e: Exception) { null }
                else -> null
            }
            obj?.let {
                val colorStr = it.optString("theme_color", "")
                val color = parseHexColor(colorStr)
                if (color != null) {
                    _events.tryEmit(SocketEvent.ThemeUpdate(color))
                }
            }
        }

        onData(s, "manager_data") { data ->
            when (data) {
                is JSONObject -> _events.tryEmit(SocketEvent.ManagerDataObj(data))
                is JSONArray -> _events.tryEmit(SocketEvent.ManagerDataArr(data))
                is String -> try {
                    if (data.startsWith("{")) _events.tryEmit(SocketEvent.ManagerDataObj(JSONObject(data)))
                    else if (data.startsWith("[")) _events.tryEmit(SocketEvent.ManagerDataArr(JSONArray(data)))
                } catch(e: Exception) {}
            }
        }

        onData(s, "stats") { data ->
            val bytes = when (data) {
                is ByteArray -> data
                is String -> encryptionKey?.let { key -> CryptoUtils.decryptToBytes(data, key) }
                else -> null
            }

            if (bytes != null) {
                try {
                    val map = MessagePackDecoder.decode(bytes)
                    _events.tryEmit(SocketEvent.StatsBinary(map))
                } catch (e: Exception) {
                    Log.e("PcSocketClient", "MessagePack decoding error", e)
                }
            }
        }

        onData(s, "stats_json") { data ->
            val obj = when(data) {
                is JSONObject -> data
                is String -> try { JSONObject(data) } catch(e: Exception) { null }
                else -> null
            }
            obj?.let {
                _events.tryEmit(SocketEvent.StatsJson(it))
            }
        }

        onData(s, "yandex_config") { data ->
            Log.i("PcSocketClient", "YANDEX CONFIG received. Type: ${data.javaClass.simpleName}")
            val obj = when(data) {
                is JSONObject -> data
                is String -> try { JSONObject(data) } catch(e: Exception) { null }
                else -> null
            }
            obj?.let {
                _events.tryEmit(SocketEvent.YandexConfig(it))
            }
        }
    }

    private fun onData(s: Socket, event: String, callback: (Any) -> Unit) {
        s.on(event) { args ->
            if (args != null && args.isNotEmpty()) {
                val data = if (args.size > 1 && args[0] is String && args[0] == event) {
                    args[1]
                } else {
                    args[0]
                }
                if (data != null) {
                    callback(data)
                }
            }
        }
    }

    fun authAttempt(password: String) {
        val payload = JSONObject().apply { put("code", password) }
        socket?.emit("auth_attempt", payload)
    }

    fun authorize(token: String) {
        val payload = JSONObject().apply { put("token", token) }
        socket?.emit("authorize", payload)
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
