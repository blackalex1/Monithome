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
        .pingInterval(30, TimeUnit.SECONDS)
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
    
    @Volatile
    private var yandexToken: String? = null

    fun setYandexToken(token: String?) {
        this.yandexToken = token
        Log.i(TAG, "Yandex OAuth token updated (hasToken=${token != null})")
    }
    
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
        val oldWs = activeConnections.remove(deviceId)
        oldWs?.close(1000, "Reconnecting")
        
        scope.launch {
            var currentConfig = config
            val oauthToken = yandexToken
            if (oauthToken != null) {
                val newToken = refreshGlagolToken(deviceId)
                if (newToken != null && newToken != config.token) {
                    Log.i(TAG, "Glagol token refreshed for $deviceId. Updating config.")
                    val updatedConfig = config.copy(token = newToken)
                    stationConfigs[deviceId] = updatedConfig
                    currentConfig = updatedConfig
                }
            }

            val ip = currentConfig.ip
            if (ip.isNullOrBlank()) {
                Log.w(TAG, "Cannot connect to $deviceId: IP is empty. Starting discovery...")
                discovery.discover(currentConfig)
                return@launch
            }

            Log.i(TAG, ">>> Connecting to ${currentConfig.name} ($deviceId) at wss://$ip:${currentConfig.port}")
            val request = Request.Builder().url("wss://$ip:${currentConfig.port}").build()

            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "Successfully opened socket for $deviceId")
                    activeConnections[deviceId] = webSocket
                    _events.tryEmit(YandexStationEvent.ConnectionChanged(deviceId, true))
                    sendAuthPing(webSocket, currentConfig.token, deviceId)
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
                    val wasActive = activeConnections.remove(deviceId, webSocket)
                    if (wasActive) {
                        _events.tryEmit(YandexStationEvent.ConnectionChanged(deviceId, false))
                        _events.tryEmit(YandexStationEvent.Error(deviceId, t.message ?: "Unknown socket error"))
                    }
                    
                    // Retry only if this was the active socket and config still exists
                    if (wasActive && stationConfigs.containsKey(deviceId)) {
                        scope.launch {
                            delay(10000)
                            if (stationConfigs.containsKey(deviceId) && !activeConnections.containsKey(deviceId)) {
                                Log.d(TAG, "Retrying connection for $deviceId...")
                                reconnect(deviceId)
                            }
                        }
                    }
                }
                
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.w(TAG, "Socket closed for $deviceId: $reason ($code)")
                    val wasActive = activeConnections.remove(deviceId, webSocket)
                    if (wasActive) {
                        _events.tryEmit(YandexStationEvent.ConnectionChanged(deviceId, false))
                    }
                }
            }

            client.newWebSocket(request, listener)
        }
    }

    private suspend fun refreshGlagolToken(deviceId: String): String? = withContext(Dispatchers.IO) {
        val oauthToken = yandexToken ?: return@withContext null
        Log.i(TAG, "Attempting to refresh Glagol token for $deviceId using Yandex OAuth token...")
        
        try {
            val request = Request.Builder()
                .url("https://quasar.yandex.net/glagol/device_list")
                .header("Authorization", "OAuth $oauthToken")
                .header("X-Yandex-Token", oauthToken)
                .header("User-Agent", "Mozilla/5.0")
                .build()
                
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to fetch device list from Yandex quasar: code ${response.code}")
                    return@withContext null
                }
                
                val body = response.body.string()
                val json = JSONObject(body)
                val devices = json.optJSONArray("devices") ?: return@withContext null
                
                for (i in 0 until devices.length()) {
                    val dev = devices.getJSONObject(i)
                    val id = dev.optString("id")
                    if (id == deviceId) {
                        var gToken = dev.optString("glagol_token")
                        if (gToken.isEmpty()) {
                            gToken = dev.optJSONObject("glagol")?.optString("token") ?: ""
                        }
                        
                        if (gToken.isNotEmpty()) {
                            Log.i(TAG, "Successfully obtained Glagol token for $deviceId from device list")
                            return@withContext gToken
                        }
                        
                        // If token is not in device list, try fetching it individually
                        val platform = dev.optString("platform")
                        if (platform.isNotEmpty()) {
                            return@withContext fetchIndividualGlagolToken(deviceId, platform, oauthToken)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing Glagol token for $deviceId: ${e.message}", e)
        }
        return@withContext null
    }

    private suspend fun fetchIndividualGlagolToken(deviceId: String, platform: String, oauthToken: String): String? = withContext(Dispatchers.IO) {
        Log.i(TAG, "Attempting to fetch individual Glagol token for $deviceId (platform: $platform)...")
        try {
            val request = Request.Builder()
                .url("https://quasar.yandex.ru/glagol/token?device_id=$deviceId&platform=$platform")
                .header("Authorization", "OAuth $oauthToken")
                .header("X-Yandex-Token", oauthToken)
                .header("User-Agent", "Mozilla/5.0")
                .build()
                
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body.string()
                    val json = JSONObject(body)
                    val token = json.optString("token")
                    if (token.isNotEmpty()) {
                        Log.i(TAG, "Successfully obtained individual Glagol token for $deviceId")
                        return@withContext token
                    }
                } else {
                    Log.e(TAG, "Failed to fetch individual token: code ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching individual Glagol token: ${e.message}", e)
        }
        return@withContext null
    }

    private fun sendAuthPing(ws: WebSocket, token: String, deviceId: String) {
        Log.i(TAG, "Sending initial AUTH PING to $deviceId")
        val payload = JSONObject().apply {
            put("conversationToken", token)
            put("id", UUID.randomUUID().toString())
            put("sentTime", System.currentTimeMillis())
            put("payload", JSONObject().apply { put("command", "ping") })
        }
        val sent = ws.send(payload.toString())
        if (!sent) {
            Log.e(TAG, "Failed to send initial AUTH PING to $deviceId (socket is closed or queue full)")
            return
        }
        
        scope.launch {
            delay(500)
            Log.i(TAG, "Starting getState loop for $deviceId")
            while (activeConnections[deviceId] == ws) {
                try {
                    val success = sendCommand(deviceId, "getState")
                    if (!success) {
                        Log.w(TAG, "Failed to send getState to $deviceId. Exiting loop.")
                        break
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in getState loop for $deviceId: ${e.message}")
                    break
                }
                delay(3000) // Increase delay to avoid flooding
            }
            Log.d(TAG, "Exited getState loop for $deviceId")
        }
    }

    fun sendCommand(deviceId: String, command: String, commandPayload: JSONObject? = null): Boolean {
        val config = stationConfigs[deviceId] ?: return false
        val ws = activeConnections[deviceId] ?: return false

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
        val sent = ws.send(payload.toString())
        if (!sent) {
            Log.w(TAG, "Failed to enqueue command $command to $deviceId (socket is closed or queue full)")
        }

        // Сразу запрашиваем стейт после команды для мгновенного обновления
        if (command != "getState" && sent) {
            scope.launch {
                delay(300)
                sendCommand(deviceId, "getState")
            }
        }
        return sent
    }
}
