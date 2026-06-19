package com.monithome.domain.repository

import com.monithome.domain.models.PluginInfo
import com.monithome.domain.models.CameraRequest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow

interface PluginRepository {
    val uiConfigs: StateFlow<List<PluginInfo>>
    val translations: StateFlow<Map<String, String>>
    val cameraRequests: SharedFlow<CameraRequest>
    
    fun getPluginStats(pluginId: String): StateFlow<Map<String, Any>>
    
    fun sendCommand(pluginId: String, action: String, target: String? = null, data: Any? = null)
    fun isStandaloneMode(): Boolean
}
