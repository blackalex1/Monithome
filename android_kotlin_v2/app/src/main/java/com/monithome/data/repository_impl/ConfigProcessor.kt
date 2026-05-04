package com.monithome.data.repository_impl

import android.util.Log
import com.monithome.domain.models.PluginInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

class ConfigProcessor {
    fun parseUiConfig(
        config: JSONObject,
        onTranslations: (Map<String, String>) -> Unit,
        onPlugins: (List<PluginInfo>) -> Unit
    ) {
        val transObj = config.optJSONObject("translations")
        if (transObj != null) {
            val map = mutableMapOf<String, String>()
            val keys = transObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = transObj.optString(key)
            }
            onTranslations(map)
        }

        val pluginsArr = config.optJSONArray("plugins") ?: return
        val list = mutableListOf<PluginInfo>()
        for (i in 0 until pluginsArr.length()) {
            val obj = pluginsArr.getJSONObject(i)
            list.add(
                PluginInfo(
                    id = obj.optString("id"),
                    name = obj.optString("name", "Unknown"),
                    description = obj.optString("description", ""),
                    type = obj.optString("type", "unknown"),
                    active = obj.optBoolean("active", false)
                )
            )
        }
        onPlugins(list)
    }

    fun bulkUpdate(
        updates: Map<String, Any>,
        isYandexStandalone: Boolean,
        statsFlows: MutableMap<String, MutableStateFlow<Map<String, Any>>>
    ) {
        updates.forEach { (pluginId, pluginData) ->
            if ((pluginId == "yandex_station" || pluginId == "yandex_lyrics") && isYandexStandalone) {
                return@forEach
            }
            if (pluginData is Map<*, *>) {
                val currentFlow = statsFlows.getOrPut(pluginId) { MutableStateFlow(emptyMap()) }
                @Suppress("UNCHECKED_CAST")
                val newData = (pluginData as Map<String, Any>).toMutableMap()
                newData["local_last_update"] = System.currentTimeMillis() / 1000.0
                currentFlow.value = newData
            }
        }
    }
}
