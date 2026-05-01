package com.monithome.data

import com.monithome.models.*
import com.monithome.network.StationConfig
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Репозиторий для управления состоянием всех плагинов.
 */
object PluginRepository {
    // 1. Поля и свойства (всегда наверху)
    private val _uiConfigs = MutableStateFlow<List<PluginInfo>>(emptyList())
    val uiConfigs: StateFlow<List<PluginInfo>> = _uiConfigs.asStateFlow()

    private val pluginStats = ConcurrentHashMap<String, MutableStateFlow<Map<String, Any>>>()
    private val pendingCovers = ConcurrentHashMap<String, String>() 
    private val history = ConcurrentHashMap<String, ConcurrentHashMap<String, HistoryBuffer>>()
    
    private val _lyrics = MutableStateFlow<Map<String, LyricsData>>(emptyMap())
    val lyrics: StateFlow<Map<String, LyricsData>> = _lyrics.asStateFlow()

    private val _activeLyrics = MutableStateFlow<LyricsViewState?>(null)
    val activeLyrics: StateFlow<LyricsViewState?> = _activeLyrics.asStateFlow()

    private var yandexDeviceFilter: Set<String>? = null

    fun showLyrics(pluginId: String, deviceId: String) {
        _activeLyrics.value = LyricsViewState(pluginId, deviceId)
    }

    fun hideLyrics() {
        _activeLyrics.value = null
    }

    // 2. Методы управления конфигурацией
    fun updateUiConfigs(configs: List<PluginInfo>) {
        _uiConfigs.value = configs
    }

    fun setYandexFilter(deviceIds: Set<String>?) {
        yandexDeviceFilter = deviceIds
    }

    fun getPluginStats(pluginId: String): StateFlow<Map<String, Any>> {
        return pluginStats.getOrPut(pluginId) {
            MutableStateFlow(emptyMap())
        }.asStateFlow()
    }

    fun getHistory(pluginId: String): Map<String, List<Float>> {
        return history[pluginId]?.mapValues { it.value.getValues() } ?: emptyMap()
    }

    fun getLyricsForDevice(deviceId: String): LyricsData? {
        return _lyrics.value[deviceId]
    }

    // 3. Методы обновления данных
    fun bulkUpdate(updates: Map<String, Any>) {
        updates.forEach { (pId, data) ->
            if (pId == "_server_time") return@forEach
            if (data is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                updateStats(pId, data as Map<String, Any>)
            }
        }
    }


    fun updateStats(pluginId: String, data: Map<String, Any>) {
        val flow = pluginStats.getOrPut(pluginId) {
            MutableStateFlow(emptyMap())
        }
        
        var finalData = data
        
        // Яндекс Станция: слияние данных для предотвращения мерцания в direct-режиме
        if (pluginId == "yandex_station" && data.containsKey("devices")) {
            val oldData = flow.value
            @Suppress("UNCHECKED_CAST")
            val oldDevices = oldData["devices"] as? List<Map<String, Any>>
            @Suppress("UNCHECKED_CAST")
            var newDevices = (data["devices"] as? List<Map<String, Any>>)?.toMutableList()
            
            // Фильтрация устройств: оставляем только выбранные
            yandexDeviceFilter?.let { filter ->
                newDevices = newDevices?.filter { filter.contains(it["id"]?.toString()) }?.toMutableList()
            }
            
            if (newDevices != null) {
                newDevices!!.forEachIndexed { idx, newDev ->
                    val dId = newDev["id"]?.toString() ?: ""
                    val oldDev = oldDevices?.find { it["id"] == dId }
                    
                    if (newDev["status"] == "direct") {
                        // Это обновление от самого планшета (прямое управление)
                        // Принимаем его целиком
                        newDevices[idx] = newDev
                    } else if (oldDev != null && oldDev["status"] == "direct" && yandexDeviceFilter != null) {
                        // Это обновление от сервера, но у нас есть приоритетные данные планшета
                        android.util.Log.d("PluginRepo", "Ignoring server update for $dId (status is direct)")
                        newDevices[idx] = oldDev + mapOf("status" to "direct")
                    } else {
                        if (pluginId == "yandex_station") {
                            android.util.Log.d("PluginRepo", "Yandex Update [$dId]: title=${newDev["title"]}, playing=${newDev["playing"]}")
                        }
                        // Обычный режим или обновление обложек
                        val pendingKey = "$pluginId:$dId"
                        if (pendingCovers.containsKey(pendingKey)) {
                            val updated = newDev.toMutableMap()
                            updated["cover"] = pendingCovers.remove(pendingKey)!!
                            newDevices[idx] = updated
                        } else if (oldDev != null) {
                            val oldCover = oldDev["cover"] as? String
                            if (!oldCover.isNullOrEmpty() && newDev["cover"] == null) {
                                val updated = newDev.toMutableMap()
                                updated["cover"] = oldCover
                                newDevices[idx] = updated
                            }
                        }
                    }
                }
                finalData = data + mapOf("devices" to newDevices)
            }
        }

        // Яндекс Тексты: агрессивное извлечение
        if (pluginId == "yandex_lyrics" && data.containsKey("devices")) {
            try {
                val gson = Gson()
                val devicesJson = gson.toJson(data["devices"])
                val type = object : com.google.gson.reflect.TypeToken<Map<String, LyricsData>>() {}.type
                val devicesMap: Map<String, LyricsData>? = gson.fromJson(devicesJson, type)
                
                devicesMap?.forEach { (dId, rawData) ->
                    processLyrics(dId, rawData)
                }
            } catch (e: Exception) {
                android.util.Log.e("PluginRepo", "Failed to extract lyrics: ${e.message}")
            }
        }

        val finalDataWithTime = finalData.toMutableMap().apply {
            put("local_last_update", System.currentTimeMillis() / 1000.0)
        }
        flow.value = flow.value + finalDataWithTime
        updateHistory(pluginId, finalData)
    }

    fun clearDirectStatus(pluginId: String) {
        if (pluginId == "yandex_station") {
            yandexDeviceFilter = null
        }
        val flow = pluginStats[pluginId] ?: return
        val oldData = flow.value
        @Suppress("UNCHECKED_CAST")
        val devices = oldData["devices"] as? List<Map<String, Any>> ?: return
        
        val newDevices = devices.map { dev ->
            dev.toMutableMap().apply { remove("status") }
        }
        flow.value = oldData + mapOf("devices" to newDevices)
        android.util.Log.i("PluginRepo", "Cleared direct status and filter for $pluginId")
    }

    /**
     * Универсальный парсер LRC
     */
    private fun parseLrc(lrcText: String): List<LyricTiming> {
        val timings = mutableListOf<LyricTiming>()
        // Поддерживает [mm:ss.xx], [mm:ss:xx], [mm:ss]
        val regex = Regex("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\](.*)")
        
        lrcText.lines().forEach { line ->
            val match = regex.find(line)
            if (match != null) {
                try {
                    val min = match.groupValues[1].toLong()
                    val sec = match.groupValues[2].toLong()
                    val msStr = match.groupValues[3]
                    val text = match.groupValues[4].trim()
                    
                    val ms = if (msStr.isEmpty()) 0L else when (msStr.length) {
                        1 -> msStr.toLong() * 100
                        2 -> msStr.toLong() * 10
                        else -> msStr.toLong()
                    }
                    
                    val totalMs = (min * 60 * 1000) + (sec * 1000) + ms
                    if (text.isNotEmpty() || timings.isNotEmpty()) {
                        timings.add(LyricTiming(totalMs, text))
                    }
                } catch (e: Exception) {}
            }
        }
        return timings.sortedBy { it.time }
    }

    private fun updateHistory(pluginId: String, data: Map<String, Any>) {
        val pluginHistory = history.getOrPut(pluginId) { ConcurrentHashMap() }
        data.forEach { (key, value) ->
            if (value is Number) {
                val buffer = pluginHistory.getOrPut(key) { HistoryBuffer(30) }
                buffer.push(value.toFloat())
            }
        }
    }

    private fun processLyrics(deviceId: String, rawData: LyricsData) {
        var lyricsData = rawData
        // Парсим LRC, если есть текст, но нет таймингов
        if (!lyricsData.lyrics.isNullOrEmpty() && lyricsData.timings.isNullOrEmpty()) {
            val parsedTimings = parseLrc(lyricsData.lyrics)
            if (parsedTimings.isNotEmpty()) {
                lyricsData = lyricsData.copy(timings = parsedTimings)
            }
        }

        // Обновляем состояние
        val currentMap = _lyrics.value.toMutableMap()
        currentMap[deviceId] = lyricsData
        _lyrics.value = currentMap
        android.util.Log.d("PluginRepo", "Lyrics updated for $deviceId (lines=${lyricsData.timings?.size ?: 0})")
    }

    fun handlePluginEvent(pluginId: String, event: String, data: Any) {
        when (event) {
            "lyrics" -> {
                try {
                    val jsonStr = data.toString()
                    val json = org.json.JSONObject(jsonStr)
                    val deviceId = if (json.has("device_id")) json.getString("device_id") else "all"
                    val lyricsObj = if (json.has("data")) json.get("data").toString() else jsonStr
                    val lyricsData = Gson().fromJson(lyricsObj, LyricsData::class.java)
                    
                    processLyrics(deviceId, lyricsData)
                } catch (e: Exception) {
                    android.util.Log.e("PluginRepo", "Lyrics parse error: ${e.message}")
                }
            }
            "cover" -> {
                try {
                    val jsonStr = data.toString()
                    val json = org.json.JSONObject(jsonStr)
                    val innerData = if (json.has("data")) json.getJSONObject("data") else json
                    
                    val cover = if (innerData.has("cover") && !innerData.isNull("cover")) innerData.getString("cover") else null
                    val deviceId = if (innerData.has("device_id") && !innerData.isNull("device_id")) innerData.getString("device_id") else null
                    val title = if (innerData.has("title") && !innerData.isNull("title")) innerData.getString("title") else ""

                    if (cover != null) {
                        if (deviceId != null) {
                            val flow = pluginStats.getOrPut(pluginId) { MutableStateFlow(emptyMap()) }
                            @Suppress("UNCHECKED_CAST")
                            val devices = (flow.value["devices"] as? List<Map<String, Any>>)?.toMutableList() ?: mutableListOf()
                            val idx = devices.indexOfFirst { it["id"] == deviceId }
                            
                            if (idx >= 0) {
                                val updated = devices[idx].toMutableMap()
                                updated["cover"] = cover
                                devices[idx] = updated
                                flow.value = flow.value.toMutableMap().apply { put("devices", devices) }
                            } else {
                                pendingCovers["$pluginId:$deviceId"] = cover
                            }
                        } else {
                            updateStats(pluginId, mapOf("cover" to cover))
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("PluginRepo", "Cover parse error: ${e.message}")
                }
            }
            "yandex_config" -> {
                try {
                    val json = if (data is org.json.JSONObject) data else org.json.JSONObject(data.toString())
                    if (json.has("devices")) {
                        val devicesArray = json.getJSONArray("devices")
                        val configs = mutableListOf<StationConfig>()
                        for (i in 0 until devicesArray.length()) {
                            val obj = devicesArray.getJSONObject(i)
                            configs.add(StationConfig(
                                deviceId = obj.getString("id"),
                                token = obj.getString("glagol_token"),
                                name = obj.getString("name"),
                                ip = if (obj.has("ip")) obj.getString("ip") else null
                            ))
                        }
                        com.monithome.network.YandexStationManager.updateConfigs(configs)
                        android.util.Log.d("PluginRepo", "Yandex direct config received: ${configs.size} devices")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("PluginRepo", "Yandex config parse error: ${e.message}")
                }
            }
        }
    }

    fun jsonToMap(json: org.json.JSONObject): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            var value = json.get(key)
            if (value is org.json.JSONArray) {
                value = jsonArrayToList(value)
            } else if (value is org.json.JSONObject) {
                value = jsonToMap(value)
            }
            map[key] = value
        }
        return map
    }

    private fun jsonArrayToList(array: org.json.JSONArray): List<Any> {
        val list = mutableListOf<Any>()
        for (i in 0 until array.length()) {
            var value = array.get(i)
            if (value is org.json.JSONArray) {
                value = jsonArrayToList(value)
            } else if (value is org.json.JSONObject) {
                value = jsonToMap(value)
            }
            list.add(value)
        }
        return list
    }
}
