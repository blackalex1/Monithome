package com.monithome.network

import com.monithome.data.PluginRepository
import com.monithome.models.PluginInfo
import com.google.gson.Gson
import org.json.JSONArray
import org.json.JSONObject

object SocketDataHandler {
    private val gson = Gson()
    var selectedYandexDevices: List<String> = emptyList()
        private set
    var isYandexStandalone: Boolean = false
        private set

    fun updateStandalone(enabled: Boolean) {
        if (isYandexStandalone != enabled) {
            android.util.Log.i("SocketDataHandler", "!!! YANDEX MODE CHANGE: Direct Control is now ${if (enabled) "ENABLED" else "DISABLED"} !!!")
        }
        isYandexStandalone = enabled
    }

    fun handleManagerDataObject(data: JSONObject) {
        try {
            if (data.has("all_plugins")) {
                val pluginsList = mutableListOf<PluginInfo>()
                val allPlugins = data.get("all_plugins")
                
                if (allPlugins is JSONObject) {
                    allPlugins.keys().forEach { key ->
                        val pJson = allPlugins.getJSONObject(key)
                        val info = gson.fromJson(pJson.toString(), PluginInfo::class.java)
                        pluginsList.add(info)
                        
                        if (key == "yandex_station") {
                            handleYandexPluginConfig(pJson)
                        }
                    }
                } else if (allPlugins is JSONArray) {
                    for (i in 0 until allPlugins.length()) {
                        val pJson = allPlugins.getJSONObject(i)
                        val info = gson.fromJson(pJson.toString(), PluginInfo::class.java)
                        pluginsList.add(info)
                        
                        if (info.id == "yandex_station") {
                            handleYandexPluginConfig(pJson)
                        }
                    }
                }
                
                if (pluginsList.isNotEmpty()) {
                    registerPluginListeners(pluginsList)
                    PluginRepository.updateUiConfigs(pluginsList)
                }
            }
            
            val master = data.optJSONObject("master_config")
            val langCode = master?.optString("language", "ru") ?: "ru"
            val lang = if (langCode == "en") com.monithome.data.AppLanguage.ENGLISH else com.monithome.data.AppLanguage.RUSSIAN
            com.monithome.data.LanguageManager.setLanguage(lang)
        } catch (e: Exception) {
            android.util.Log.e("SocketDataHandler", "Error in handleManagerDataObject: ${e.message}")
        }
    }

    fun handleManagerDataArray(array: JSONArray) {
        try {
            val pluginsList = mutableListOf<PluginInfo>()
            for (i in 0 until array.length()) {
                val pJson = array.getJSONObject(i)
                val info = gson.fromJson(pJson.toString(), PluginInfo::class.java)
                pluginsList.add(info)
                
                if (info.id == "yandex_station") {
                    handleYandexPluginConfig(pJson)
                }
            }
            registerPluginListeners(pluginsList)
            PluginRepository.updateUiConfigs(pluginsList)
        } catch (e: Exception) {
            android.util.Log.e("SocketDataHandler", "Error in handleManagerDataArray: ${e.message}")
        }
    }

    private fun handleYandexPluginConfig(pJson: JSONObject) {
        val config = pJson.optJSONObject("config")
        val isEnabled = config?.optBoolean("tablet_control", false) ?: pJson.optBoolean("tablet_control", false)
        
        val selectedIds = pJson.optJSONArray("selected_device_ids") ?: 
                         config?.optJSONArray("selected_device_ids")
        
        if (selectedIds != null) {
            val ids = mutableListOf<String>()
            for (i in 0 until selectedIds.length()) ids.add(selectedIds.getString(i))
            selectedYandexDevices = ids
            PluginRepository.setYandexFilter(ids.toSet())
            
            selectedYandexDevices = ids
            PluginRepository.setYandexFilter(ids.toSet())
        }
        
        isYandexStandalone = isEnabled
        checkYandexStandalone(isEnabled)
    }

    private fun checkYandexStandalone(enabled: Boolean) {
        if (!enabled) {
            YandexStationManager.stopAll()
        }
    }

    fun registerPluginListeners(plugins: List<PluginInfo>) {
        plugins.forEach { plugin ->
            val pId = plugin.id ?: return@forEach // Исправление ошибки типов (String?)
            val eventName = "plugin_event:$pId"
            
            SocketManager.getSocket()?.off(eventName)
            SocketManager.getSocket()?.on(eventName) { args ->
                try {
                    val eventData = JsonParser.safeParseJson(args) as? JSONObject ?: return@on
                    val eventNameInside = eventData.optString("event")
                    
                    if (pId == "yandex_station" && eventNameInside == "yandex_config" && eventData.has("data")) {
                        val data = eventData.optJSONObject("data")
                        if (data != null) SocketManager.handleYandexConfigEvent(data)
                    } else {
                        PluginRepository.handlePluginEvent(pId, eventNameInside, eventData)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SocketDataHandler", "EVENT_ERROR for $pId: ${e.message}")
                }
            }
            android.util.Log.d("SocketDataHandler", "Registered listener for $eventName")
        }
    }
}
