package com.monithome.domain.repository

import com.monithome.domain.models.PluginInfo
import kotlinx.coroutines.flow.StateFlow

interface PluginRepository {
    val uiConfigs: StateFlow<List<PluginInfo>>
    
    fun getPluginStats(pluginId: String): StateFlow<Map<String, Any>>
    
    fun sendCommand(pluginId: String, action: String, target: String? = null, data: Any? = null)
    fun isStandaloneMode(): Boolean
}
