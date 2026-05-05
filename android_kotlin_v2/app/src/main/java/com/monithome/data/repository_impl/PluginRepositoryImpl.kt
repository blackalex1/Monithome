package com.monithome.data.repository_impl

import android.util.Log
import com.monithome.core.crypto.CryptoUtils
import com.monithome.data.network.socket.PcSocketClient
import com.monithome.data.network.socket.SocketEvent
import com.monithome.data.network.yandex.YandexStationClient
import com.monithome.data.network.yandex.YandexLyricsClient
import com.monithome.data.network.yandex.YandexStationEvent
import com.monithome.domain.models.PluginInfo
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
    private val yandexClient: YandexStationClient,
    private val yandexLyricsClient: YandexLyricsClient
) : PluginRepository {

    private val scope = CoroutineScope(Dispatchers.Default)

    private val _uiConfigs = MutableStateFlow<List<PluginInfo>>(emptyList())
    override val uiConfigs: StateFlow<List<PluginInfo>> = _uiConfigs.asStateFlow()

    private val _translations = MutableStateFlow<Map<String, String>>(emptyMap())
    override val translations: StateFlow<Map<String, String>> = _translations.asStateFlow()

    private val statsFlows = ConcurrentHashMap<String, MutableStateFlow<Map<String, Any>>>()
    
    // Модули обработки
    private val yandexProcessor = YandexStandaloneProcessor(yandexLyricsClient)
    private val configProcessor = ConfigProcessor()
    private val yandexConfigHandler = YandexConfigHandler(yandexClient)

    private var encryptionKey: String? = null
    private var isYandexStandalone = false
    private var yandexToken: String? = null
    private var allowedDeviceIds = emptySet<String>()

    init {
        scope.launch {
            pcSocketClient.events.collect { event -> handlePcEvent(event) }
        }
        scope.launch {
            yandexClient.events.collect { event ->
                yandexProcessor.handleYandexEvent(event, isYandexStandalone, yandexToken, statsFlows)
            }
        }
    }

    override fun getPluginStats(pluginId: String): StateFlow<Map<String, Any>> {
        return statsFlows.getOrPut(pluginId) { MutableStateFlow(emptyMap()) }.asStateFlow()
    }

    override fun isStandaloneMode(): Boolean = isYandexStandalone

    override fun sendCommand(pluginId: String, action: String, target: String?, data: Any?) {
        if (pluginId == "yandex_station" && target != null && isYandexStandalone) {
            val yandexCommand = when (action) {
                "play_pause" -> {
                    val currentStats = statsFlows["yandex_station"]?.value ?: emptyMap()
                    @Suppress("UNCHECKED_CAST")
                    val devices = currentStats["devices"] as? List<Map<String, Any>>
                    val device = devices?.find { it["id"] == target }
                    val isPlaying = device?.get("playing") as? Boolean ?: false
                    if (isPlaying) "stop" else "play"
                }
                "next_track" -> "next"
                "prev_track" -> "prev"
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

        // PC Command
        var payloadData = data
        val key = encryptionKey
        if (key != null && data != null && (pluginId == "yandex_station" || action.contains("token"))) {
            val encrypted = CryptoUtils.encrypt(data.toString(), key)
            if (encrypted != null) payloadData = JSONObject().put("encrypted", encrypted)
        }
        pcSocketClient.sendCommand(pluginId, action, target, payloadData)
    }

    private fun handlePcEvent(event: SocketEvent) {
        try {
            when (event) {
                is SocketEvent.AuthSuccess -> {
                    encryptionKey = event.encryptionKey
                }
                is SocketEvent.UiConfig -> {
                    configProcessor.parseUiConfig(event.config, { _translations.value = it }, { _uiConfigs.value = it })
                }
                is SocketEvent.StatsJson -> {
                    val stats = event.data.optJSONObject("stats")
                    if (stats != null) configProcessor.bulkUpdate(JsonUtils.jsonToMap(stats), isYandexStandalone, statsFlows)
                }
                is SocketEvent.StatsBinary -> {
                    @Suppress("UNCHECKED_CAST")
                    val stats = event.map["stats"] as? Map<String, Any>
                    if (stats != null) configProcessor.bulkUpdate(stats, isYandexStandalone, statsFlows)
                }
                is SocketEvent.YandexConfig -> {
                    yandexConfigHandler.handleYandexConfig(
                        event.data, encryptionKey, statsFlows,
                        { isYandexStandalone = it }, { yandexToken = it }, { allowedDeviceIds = it }
                    )
                }
                else -> {}
            }
        } catch (e: Exception) {
            Log.e("PluginRepo", "Error: ${e.message}")
        }
    }
}
