package com.monithome.presentation.dashboard.handlers

import com.monithome.domain.models.LyricLine
import com.monithome.domain.models.PluginInfo
import com.monithome.domain.repository.PluginRepository
import com.monithome.presentation.dashboard.MediaSource
import com.monithome.presentation.dashboard.MediaUIState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class MediaHandler(
    private val pluginRepository: PluginRepository
) {
    fun observeMedia(
        configs: List<PluginInfo>,
        manualSelectedSourceId: Flow<String?>
    ): Flow<Pair<MediaUIState, List<LyricLine>>> {
        val mediaPlugins = configs.filter { it.type == "media_source" && it.active }
        if (mediaPlugins.isEmpty()) {
            return combine(MutableStateFlow(MediaUIState.Empty), MutableStateFlow(emptyList<LyricLine>())) { m, l -> m to l }
        }

        val statsFlows = mediaPlugins.map { plugin ->
            pluginRepository.getPluginStats(plugin.id).map { plugin to it }
        }

        val mediaDataFlow = combine(combine(statsFlows) { it.toList() }, manualSelectedSourceId) { pairs, selectedId ->
            val allSources = mutableListOf<MediaSource>()
            pairs.forEach { (plugin, stats) ->
                val devices = stats["devices"] as? List<Map<String, Any>>
                if (!devices.isNullOrEmpty()) {
                    devices.forEach { dev ->
                        val devId = dev["id"] as? String ?: "default"
                        allSources.add(MediaSource(plugin.id, devId, dev["name"] as? String ?: plugin.name))
                    }
                } else {
                    allSources.add(MediaSource(plugin.id, (stats["device_id"] as? String) ?: "pc", plugin.name))
                }
            }

            val targetPair = pairs.find { (plugin, stats) ->
                val devices = stats["devices"] as? List<Map<String, Any>>
                if (!devices.isNullOrEmpty()) devices.any { "${plugin.id}:${it["id"]}" == selectedId }
                else "${plugin.id}:${stats["device_id"] ?: "pc"}" == selectedId
            } ?: if (selectedId == null) pairs.firstOrNull() else null
            
            if (targetPair != null) {
                val (plugin, stats) = targetPair
                val devices = stats["devices"] as? List<Map<String, Any>>
                val deviceStats = if (!devices.isNullOrEmpty()) {
                    devices.find { "${plugin.id}:${it["id"]}" == selectedId } ?: devices.first()
                } else stats

                MediaUIState(
                    sources = allSources,
                    selectedSourceId = selectedId ?: "${plugin.id}:${deviceStats["id"] ?: deviceStats["device_id"] ?: "pc"}",
                    title = (deviceStats["title"] as? String) ?: "",
                    artist = (deviceStats["artist"] as? String) ?: (deviceStats["subtitle"] as? String) ?: "",
                    coverUrl = (deviceStats["cover"] as? String) ?: "",
                    isPlaying = (deviceStats["playing"] as? Boolean) ?: false,
                    baseProgress = (deviceStats["progress"] as? Number)?.toDouble() ?: 0.0,
                    duration = (deviceStats["duration"] as? Number)?.toDouble() ?: 0.0,
                    lastUpdateUnixTime = (deviceStats["local_last_update"] as? Number)?.toDouble() ?: (deviceStats["last_update"] as? Number)?.toDouble() ?: (System.currentTimeMillis() / 1000.0),
                    volume = (deviceStats["volume"] as? Number)?.toInt() ?: 0,
                    isLyricsActive = configs.any { it.id == "yandex_lyrics" && it.active }
                )
            } else {
                MediaUIState.Empty.copy(sources = allSources, selectedSourceId = selectedId, isLyricsActive = configs.any { it.id == "yandex_lyrics" && it.active })
            }
        }

        val lyricsFlow = pluginRepository.getPluginStats("yandex_lyrics").map { stats ->
            val devices = stats["devices"] as? Map<String, Any>
            val selectedId = (manualSelectedSourceId as? MutableStateFlow)?.value ?: ""
            val actualDeviceId = selectedId.substringAfter(":")
            val data = devices?.get(actualDeviceId) as? Map<String, Any>
            
            (data?.get("timings") as? List<Map<String, Any>>)?.map {
                LyricLine(timeMs = (it["time"] as? Number)?.toLong() ?: 0L, text = (it["text"] as? String) ?: "")
            } ?: emptyList()
        }

        return combine(mediaDataFlow, lyricsFlow) { m, l -> m to l }
    }
}
