package com.monithome.data

import com.monithome.models.*
import com.monithome.network.StationConfig
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*

/**
 * Репозиторий для управления состоянием всех плагинов.
 */
object PluginRepository {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gson = com.google.gson.Gson()
    
    // Троттлинг обновлений для экономии ресурсов
    private val lastUpdateTimes = ConcurrentHashMap<String, Long>()
    private const val MIN_UPDATE_INTERVAL_MS = 100 // Максимум 10 обновлений в секунду на плагин
    
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

    fun getPluginStatsBlocking(pluginId: String): Map<String, Any> {
        return pluginStats[pluginId]?.value ?: emptyMap()
    }

    fun getHistory(pluginId: String): Map<String, List<Float>> {
        return history[pluginId]?.mapValues { it.value.getValues() } ?: emptyMap()
    }

    // 3. Методы обновления данных
    fun bulkUpdate(updates: Map<String, Any>) {
        repositoryScope.launch {
            // ОПТИМИЗАЦИЯ: Обрабатываем весь пакет в одной корутине
            updates.forEach { (pId, data) ->
                if (pId == "_server_time") return@forEach
                if (data is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    updateStatsInternal(pId, data as Map<String, Any>)
                }
            }
        }
    }

    fun updateStats(pluginId: String, data: Map<String, Any>) {
        repositoryScope.launch {
            updateStatsInternal(pluginId, data)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun updateStatsInternal(pluginId: String, data: Map<String, Any>) {
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
                newDevices.forEachIndexed { idx, newDev ->
                    val dId = newDev["id"]?.toString() ?: ""
                    val oldDev = oldDevices?.find { it["id"] == dId }
                    
                    if (newDev["status"] == "direct") {
                        newDevices[idx] = newDev
                    } else if (oldDev != null && oldDev["status"] == "direct" && yandexDeviceFilter != null) {
                        newDevices[idx] = oldDev + mapOf("status" to "direct")
                    } else {
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

        // Яндекс Тексты: прямое извлечение из Map
        if (pluginId == "yandex_lyrics") {
            val devicesData = data["devices"] as? Map<*, *>
            val newLyricsMap = mutableMapOf<String, LyricsData>()
            devicesData?.forEach { (dId, rawValue) ->
                if (dId is String && rawValue is Map<*, *>) {
                    try {
                        val lyricsData = LyricsData(
                            lyrics = rawValue["lyrics"] as? String,
                            timings = (rawValue["timings"] as? List<Map<String, Any>>)?.map { t ->
                                LyricTiming(
                                    time = (t["time"] as? Number)?.toLong(),
                                    text = t["text"] as? String
                                )
                            } ?: emptyList(),
                            trackId = rawValue["trackId"] as? String
                        )
                        newLyricsMap[dId] = processLyricsInternal(lyricsData)
                    } catch (e: Exception) {}
                }
            }
            if (newLyricsMap.isNotEmpty()) {
                val currentMap = _lyrics.value.toMutableMap()
                currentMap.putAll(newLyricsMap)
                _lyrics.value = currentMap
            }
        }

        // ТРОТТЛИНГ: Пропускаем слишком частые обновления (кроме критических событий)
        val now = System.currentTimeMillis()
        val lastTime = lastUpdateTimes[pluginId] ?: 0L
        if (now - lastTime < MIN_UPDATE_INTERVAL_MS && !finalData.containsKey("devices")) {
            return
        }
        lastUpdateTimes[pluginId] = now

        // СЛИЯНИЕ ДАННЫХ: Объединяем новые данные со старыми
        val oldData = flow.value
        
        // ОПТИМИЗАЦИЯ ОБЛОЖЕК: Если обложка та же самая - не обновляем её
        val filteredData = if (finalData.containsKey("cover") && oldData.containsKey("cover")) {
            if (finalData["cover"] == oldData["cover"]) {
                finalData.filterKeys { it != "cover" }
            } else finalData
        } else finalData

        if (filteredData.isEmpty()) return

        // Проверяем, изменилось ли что-то в новых данных относительно старых
        val hasChanges = filteredData.any { (k, v) -> 
            k != "local_last_update" && oldData[k] != v 
        }
        
        if (hasChanges) {
            val mergedData = HashMap<String, Any>(oldData).apply { putAll(filteredData) }
            mergedData["local_last_update"] = now / 1000.0
            flow.value = mergedData
            updateHistory(pluginId, filteredData)
        }
    }

    fun updateDirectStatus(pluginId: String, deviceId: String, status: Map<String, Any>) {
        val flow = pluginStats.getOrPut(pluginId) {
            MutableStateFlow(emptyMap())
        }
        
        val oldData = flow.value
        @Suppress("UNCHECKED_CAST")
        val devices = (oldData["devices"] as? List<Map<String, Any>>)?.toMutableList() ?: return
        
        val idx = devices.indexOfFirst { it["id"] == deviceId }
        if (idx >= 0) {
            val updated = devices[idx].toMutableMap()
            updated.putAll(status)
            updated["status"] = "direct"
            updated["local_last_update"] = System.currentTimeMillis() / 1000.0
            devices[idx] = updated
            flow.value = oldData.toMutableMap().apply { 
                put("devices", devices)
                put("local_last_update", System.currentTimeMillis() / 1000.0)
            }
        }
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
                // Сохраняем историю только для реальных метрик, чтобы не забивать память
                val lowKey = key.lowercase()
                if (lowKey.contains("cpu") || lowKey.contains("gpu") || 
                    lowKey.contains("ram") || lowKey.contains("temp") || 
                    lowKey.contains("load") || lowKey.contains("usage")) {
                    
                    val buffer = pluginHistory.getOrPut(key) { HistoryBuffer(60) }
                    buffer.push(value.toFloat())
                }
            }
        }
    }

    private fun processLyricsInternal(rawData: LyricsData): LyricsData {
        var lyricsData = rawData
        // Парсим LRC, если есть текст, но нет таймингов
        if (!lyricsData.lyrics.isNullOrEmpty() && lyricsData.timings.isNullOrEmpty()) {
            val parsedTimings = parseLrc(lyricsData.lyrics)
            if (parsedTimings.isNotEmpty()) {
                lyricsData = lyricsData.copy(timings = parsedTimings)
            }
        }
        return lyricsData
    }

    fun handlePluginEvent(pluginId: String, event: String, data: Any) {
        repositoryScope.launch {
            handlePluginEventInternal(pluginId, event, data)
        }
    }

    private suspend fun handlePluginEventInternal(pluginId: String, event: String, data: Any) {
        when (event) {
            "lyrics" -> {
                try {
                    val lyricsObj = if (data is org.json.JSONObject) {
                        data
                    } else {
                        org.json.JSONObject(data.toString())
                    }
                    
                    val deviceId = if (lyricsObj.has("device_id")) lyricsObj.getString("device_id") else "all"
                    val actualData = if (lyricsObj.has("data")) lyricsObj.get("data").toString() else lyricsObj.toString()
                    val lyricsData = gson.fromJson(actualData, LyricsData::class.java)
                    
                    val processed = processLyricsInternal(lyricsData)
                    val currentMap = _lyrics.value.toMutableMap()
                    currentMap[deviceId] = processed
                    _lyrics.value = currentMap
                } catch (e: Exception) {}
            }
            "cover" -> {
                try {
                    val json = if (data is org.json.JSONObject) data else org.json.JSONObject(data.toString())
                    val innerData = if (json.has("data")) json.getJSONObject("data") else json
                    
                    val cover = if (innerData.has("cover") && !innerData.isNull("cover")) innerData.getString("cover") else null
                    val deviceId = if (innerData.has("device_id") && !innerData.isNull("device_id")) innerData.getString("device_id") else null

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
                                // ОПТИМИЗАЦИЯ: Ограничиваем размер очереди обложек, чтобы не забить память если device_id не найден
                                if (pendingCovers.size < 20) {
                                    pendingCovers["$pluginId:$deviceId"] = cover
                                }
                            }
                        } else {
                            updateStats(pluginId, mapOf("cover" to cover))
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("PluginRepo", "Cover parse error: ${e.message}")
                }
            }
            "status" -> {
                try {
                    val json = if (data is org.json.JSONObject) data else org.json.JSONObject(data.toString())
                    val status = if (json.has("data")) json.get("data") else json
                    if (status is org.json.JSONObject) {
                        updateStats(pluginId, jsonToMap(status))
                    } else if (status is org.json.JSONArray) {
                        // Для плагинов, которые шлют список (например, диски)
                        updateStats(pluginId, mapOf("items" to jsonArrayToList(status)))
                    }
                } catch (e: Exception) {}
            }
            "direct_status" -> {
                try {
                    val json = if (data is org.json.JSONObject) data else org.json.JSONObject(data.toString())
                    val status = if (json.has("data")) json.getJSONObject("data") else json
                    val deviceId = if (status.has("device_id")) status.getString("device_id") else null
                    if (deviceId != null) {
                        updateDirectStatus(pluginId, deviceId, jsonToMap(status))
                    }
                } catch (e: Exception) {}
            }
            "yandex_config" -> {
                try {
                    val json = (data as? org.json.JSONObject) ?: org.json.JSONObject(data.toString())
                    val inner = if (json.has("data")) json.getJSONObject("data") else json
                    if (inner.has("devices")) {
                        val devicesArray = inner.getJSONArray("devices")
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
                    }
                } catch (e: Exception) {}
            }
            else -> {
                // Универсальный парсер для всех остальных событий
                try {
                    val json = if (data is org.json.JSONObject) data else org.json.JSONObject(data.toString())
                    updateStats(pluginId, jsonToMap(json))
                } catch (e: Exception) {}
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
