package com.monithome.data.repository_impl

import android.util.Log
import com.monithome.core.crypto.CryptoUtils
import com.monithome.data.network.yandex.YandexStationClient
import com.monithome.domain.models.StationConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

class YandexConfigHandler(
    private val yandexClient: YandexStationClient
) {
    fun handleYandexConfig(
        data: JSONObject,
        encryptionKey: String?,
        statsFlows: MutableMap<String, MutableStateFlow<Map<String, Any>>>,
        onStandaloneChanged: (Boolean) -> Unit,
        onTokenChanged: (String?) -> Unit,
        onAllowedDevicesChanged: (Set<String>) -> Unit
    ) {
        var finalData = data
        val encrypted = data.optString("encrypted")
        if (encrypted.isNotEmpty() && encryptionKey != null) {
            val decrypted = CryptoUtils.decrypt(encrypted, encryptionKey)
            if (decrypted != null) {
                try {
                    finalData = JSONObject(decrypted)
                } catch (e: Exception) {
                    Log.e("YandexConfig", "Error parsing decrypted config: ${e.message}")
                }
            }
        }

        val isStandalone = finalData.optBoolean("enabled", false)
        onStandaloneChanged(isStandalone)
        
        val token = finalData.optString("yandex_token")
        onTokenChanged(if (token.isNotEmpty()) token else null)

        val devicesArr = finalData.optJSONArray("devices")
        val allowedIds = mutableSetOf<String>()
        if (devicesArr != null) {
            val configs = mutableListOf<StationConfig>()
            val initialDevicesList = mutableListOf<Map<String, Any>>()
            
            for (i in 0 until devicesArr.length()) {
                val dev = devicesArr.getJSONObject(i)
                val id = dev.optString("id")
                val name = dev.optString("name").ifEmpty { "Яндекс Станция" }
                allowedIds.add(id)
                configs.add(
                    StationConfig(
                        deviceId = id,
                        name = name,
                        token = dev.optString("glagol_token"),
                        ip = dev.optString("ip"),
                        port = dev.optInt("port", 1961)
                    )
                )
                initialDevicesList.add(mapOf(
                    "id" to id,
                    "name" to name,
                    "online" to false,
                    "status" to "connecting"
                ))
            }
            
            // Предзаполняем стейт именами устройств, если мы в стэндэлоне
            if (isStandalone) {
                val flow = statsFlows.getOrPut("yandex_station") { MutableStateFlow(emptyMap()) }
                flow.update { current ->
                    val updated = current.toMutableMap()
                    updated["devices"] = initialDevicesList
                    updated
                }
            }

            onAllowedDevicesChanged(allowedIds)
            yandexClient.updateConfigs(if (isStandalone) configs else emptyList())
        }
    }
}
