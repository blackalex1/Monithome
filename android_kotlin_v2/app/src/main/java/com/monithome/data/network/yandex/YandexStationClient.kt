package com.monithome.data.network.yandex

import android.content.Context
import android.net.nsd.NsdManager
import android.util.Log
import com.monithome.domain.models.StationConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.*
import org.json.JSONObject
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

sealed class YandexStationEvent {
    data class StateUpdated(val deviceId: String, val state: JSONObject) : YandexStationEvent()
    data class Error(val deviceId: String, val error: String) : YandexStationEvent()
    data class ConnectionChanged(val deviceId: String, val isConnected: Boolean) : YandexStationEvent()
}

class YandexStationClient(context: Context) {
    private val TAG = "YandexStationClient"

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
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    
    private val _events = MutableSharedFlow<YandexStationEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<YandexStationEvent> = _events.asSharedFlow()

    private val discovery = YandexDiscovery(nsdManager) { deviceId, ip ->
        stationConfigs[deviceId]?.let { config ->
            config.ip = ip
            reconnect(deviceId)
        }
    }

    fun updateConfigs(configs: List<StationConfig>) {
        Log.i(TAG, "updateConfigs called with ${configs.size} devices")
        val newIds = configs.map { it.deviceId }.toSet()

        // 1. Remove obsolete
        stationConfigs.keys().toList().forEach { id ->
            if (!newIds.contains(id)) {
                activeConnections[id]?.close(1000, "Removed from config")
                activeConnections.remove(id)
                stationConfigs.remove(id)
                _events.tryEmit(YandexStationEvent.ConnectionChanged(id, false))
            }
        }

        // 2. Add or update
        configs.forEach { config ->
            val old = stationConfigs[config.deviceId]
            val hasConnection = activeConnections.containsKey(config.deviceId)
            Log.i(TAG, "Checking config for ${config.deviceId}: hasOld=${old != null}, hasConn=$hasConnection")
            
            if (old == null || old.token != config.token || old.ip != config.ip || !hasConnection) {
                Log.i(TAG, "Config changed or missing for ${config.deviceId}. Triggering reconnect.")
                stationConfigs[config.deviceId] = config
                reconnect(config.deviceId)
            }
        }
    }

    fun stopAll() {
        activeConnections.values.forEach { it.close(1000, "Stopped") }
        activeConnections.clear()
        stationConfigs.clear()
        scope.coroutineContext.cancelChildren()
    }

    private fun reconnect(deviceId: String) {
        val config = stationConfigs[deviceId] ?: return
        activeConnections[deviceId]?.close(1000, "Reconnecting")
        
        val ip = config.ip
        if (ip.isNullOrBlank()) {
            Log.w(TAG, "Cannot connect to $deviceId: IP is empty. Starting discovery...")
            discovery.discover(config)
            return
        }

        Log.i(TAG, ">>> Connecting to ${config.name} ($deviceId) at wss://$ip:${config.port}")
        val request = Request.Builder().url("wss://$ip:${config.port}").build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Successfully opened socket for $deviceId")
                activeConnections[deviceId] = webSocket
                _events.tryEmit(YandexStationEvent.ConnectionChanged(deviceId, true))
                sendAuthPing(webSocket, config.token, deviceId)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    var state: JSONObject? = null
                    
                    if (json.has("state")) {
                        state = json.optJSONObject("state")
                    } else if (json.has("payload")) {
                        val payload = json.optJSONObject("payload")
                        if (payload != null && payload.has("state")) {
                            state = payload.optJSONObject("state")
                        }
                    }

                    if (state != null) {
                        Log.v(TAG, "State received for $deviceId: ${state.optJSONObject("playerState")?.optString("title") ?: "no title"}")
                        _events.tryEmit(YandexStationEvent.StateUpdated(deviceId, state))
                    } else {
                        Log.d(TAG, "Message from $deviceId received but no state found: $text")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Parse error for $deviceId: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "!!! Socket failure for $deviceId: ${t.message}", t)
                activeConnections.remove(deviceId)
                _events.tryEmit(YandexStationEvent.ConnectionChanged(deviceId, false))
                _events.tryEmit(YandexStationEvent.Error(deviceId, t.message ?: "Unknown socket error"))
                
                // Retry only if config still exists
                if (stationConfigs.containsKey(deviceId)) {
                    scope.launch {
                        delay(10000)
                        if (stationConfigs.containsKey(deviceId)) {
                            Log.d(TAG, "Retrying connection for $deviceId...")
                            reconnect(deviceId)
                        }
                    }
                }
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "Socket closed for $deviceId: $reason ($code)")
                activeConnections.remove(deviceId)
                _events.tryEmit(YandexStationEvent.ConnectionChanged(deviceId, false))
            }
        }

        client.newWebSocket(request, listener)
    }

    private fun sendAuthPing(ws: WebSocket, token: String, deviceId: String) {
        Log.i(TAG, "Sending initial AUTH PING to $deviceId")
        val payload = JSONObject().apply {
            put("conversationToken", token)
            put("id", UUID.randomUUID().toString())
            put("sentTime", System.currentTimeMillis())
            put("payload", JSONObject().apply { put("command", "ping") })
        }
        ws.send(payload.toString())
        
        scope.launch {
            delay(500)
            Log.i(TAG, "Starting getState loop for $deviceId")
            while (activeConnections[deviceId] == ws) {
                try {
                    sendCommand(deviceId, "getState")
                } catch (e: Exception) {
                    Log.e(TAG, "Error in getState loop for $deviceId: ${e.message}")
                    break
                }
                delay(3000) // Increase delay to avoid flooding
            }
            Log.d(TAG, "Exited getState loop for $deviceId")
        }
    }

    fun sendCommand(deviceId: String, command: String, commandPayload: JSONObject? = null) {
        val config = stationConfigs[deviceId] ?: return
        val ws = activeConnections[deviceId] ?: return

        val payload = JSONObject().apply {
            put("conversationToken", config.token)
            put("id", UUID.randomUUID().toString())
            put("sentTime", System.currentTimeMillis())
            put("payload", JSONObject().apply { 
                put("command", command)
                if (commandPayload != null) {
                    val keys = commandPayload.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        put(key, commandPayload.get(key))
                    }
                }
            })
        }
        Log.v(TAG, "Sending command $command to $deviceId: $payload")
        ws.send(payload.toString())

        // Сразу запрашиваем стейт после команды для мгновенного обновления
        if (command != "getState") {
            scope.launch {
                delay(300)
                sendCommand(deviceId, "getState")
            }
        }
    }
}
