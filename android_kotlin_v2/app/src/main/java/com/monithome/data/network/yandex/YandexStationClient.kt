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
            if (old == null || old.token != config.token || old.ip != config.ip || !hasConnection) {
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
        activeConnections[deviceId]?.close(1000, "Reconnecting")
        val config = stationConfigs[deviceId] ?: return
        
        val ip = config.ip
        if (ip == null) {
            discovery.discover(config)
            return
        }

        Log.d(TAG, "Connecting to ${config.name} at wss://$ip:${config.port}")
        val request = Request.Builder().url("wss://$ip:${config.port}").build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                activeConnections[deviceId] = webSocket
                _events.tryEmit(YandexStationEvent.ConnectionChanged(deviceId, true))
                sendAuthPing(webSocket, config.token, deviceId)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.has("state")) {
                        _events.tryEmit(YandexStationEvent.StateUpdated(deviceId, json.getJSONObject("state")))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Parse error for $deviceId", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                activeConnections.remove(deviceId)
                _events.tryEmit(YandexStationEvent.ConnectionChanged(deviceId, false))
                _events.tryEmit(YandexStationEvent.Error(deviceId, t.message ?: "Unknown socket error"))
                scope.launch {
                    delay(10000)
                    if (stationConfigs.containsKey(deviceId)) reconnect(deviceId)
                }
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                activeConnections.remove(deviceId)
                _events.tryEmit(YandexStationEvent.ConnectionChanged(deviceId, false))
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
            delay(300)
            sendCommand(deviceId, "getState")
            while (activeConnections[deviceId] == ws) {
                delay(1000) // Опрашиваем раз в секунду для плавности
                if (activeConnections[deviceId] == ws) sendCommand(deviceId, "getState")
            }
        }
    }

    fun sendCommand(deviceId: String, command: String, commandPayload: JSONObject? = null) {
        val config = stationConfigs[deviceId] ?: return
        val ws = activeConnections[deviceId] ?: return

        val finalPayload = commandPayload ?: JSONObject()

        val payload = JSONObject().apply {
            put("conversationToken", config.token)
            put("id", UUID.randomUUID().toString())
            put("sentTime", System.currentTimeMillis())
            put("payload", JSONObject().apply { 
                put("command", command)
                val keys = finalPayload.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, finalPayload.get(key))
                }
            })
        }
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
