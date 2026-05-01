package com.monithome.data

import com.monithome.models.*
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

    // 2. Методы управления конфигурацией
    fun updateUiConfigs(configs: List<PluginInfo>) {
        _uiConfigs.value = configs
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
        
        // Яндекс Станция: слияние обложек
        if (pluginId == "yandex_station" && data.containsKey("devices")) {
            @Suppress("UNCHECKED_CAST")
            val newDevices = (data["devices"] as? List<Map<String, Any>>)?.toMutableList()
            @Suppress("UNCHECKED_CAST")
            val oldDevices = (flow.value["devices"] as? List<Map<String, Any>>)
            
            newDevices?.forEachIndexed { idx, newDev ->
                val dId = newDev["id"]?.toString() ?: ""
                val pendingKey = "$pluginId:$dId"
                
                if (pendingCovers.containsKey(pendingKey)) {
                    val updated = newDev.toMutableMap()
                    updated["cover"] = pendingCovers.remove(pendingKey)!!
                    newDevices[idx] = updated
                } else {
                    val oldDev = oldDevices?.find { it["id"] == dId }
                    val oldCover = oldDev?.get("cover") as? String
                    if (!oldCover.isNullOrEmpty()) {
                        val updated = newDev.toMutableMap()
                        updated["cover"] = oldCover
                        newDevices[idx] = updated
                    }
                }
            }
            if (newDevices != null) {
                finalData = data.toMutableMap().apply { put("devices", newDevices) }
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
                    var lyricsData = rawData
                    // Парсим LRC, если есть текст, но нет таймингов
                    if (!lyricsData.lyrics.isNullOrEmpty() && lyricsData.timings.isNullOrEmpty()) {
                        val parsedTimings = parseLrc(lyricsData.lyrics)
                        if (parsedTimings.isNotEmpty()) {
                            lyricsData = lyricsData.copy(timings = parsedTimings)
                        }
                    }

                    // Обновляем состояние в любом случае (даже если пусто), 
                    // чтобы сбросить статус "Загрузка..."
                    val currentMap = _lyrics.value.toMutableMap()
                    currentMap[dId] = lyricsData
                    _lyrics.value = currentMap
                    android.util.Log.d("PluginRepo", "Lyrics updated for $dId (lines=${lyricsData.timings?.size ?: 0})")
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

    fun handlePluginEvent(pluginId: String, event: String, data: Any) {
        when (event) {
            "lyrics" -> {
                try {
                    val jsonStr = data.toString()
                    val json = org.json.JSONObject(jsonStr)
                    val deviceId = if (json.has("device_id")) json.getString("device_id") else "all"
                    val lyricsObj = if (json.has("data")) json.get("data").toString() else jsonStr
                    var lyricsData = Gson().fromJson(lyricsObj, LyricsData::class.java)
                    
                    // Парсим LRC на лету
                    if (!lyricsData.lyrics.isNullOrEmpty() && lyricsData.timings.isNullOrEmpty()) {
                        val parsedTimings = parseLrc(lyricsData.lyrics)
                        if (parsedTimings.isNotEmpty()) {
                            lyricsData = lyricsData.copy(timings = parsedTimings)
                        }
                    }
                    
                    _lyrics.value = _lyrics.value.toMutableMap().apply {
                        put(deviceId, lyricsData)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("PluginRepo", "Lyrics parse error: ${e.message}")
                }
            }
            "cover" -> {
                try {
                    val json = org.json.JSONObject(data.toString())
                    val cover = if (json.has("cover") && !json.isNull("cover")) json.getString("cover") else null
                    val deviceId = if (json.has("device_id") && !json.isNull("device_id")) json.getString("device_id") else null

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
        }
    }
}
