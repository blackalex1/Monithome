package com.monithome.presentation.dashboard.handlers

import com.monithome.domain.models.PluginInfo
import com.monithome.domain.repository.PluginRepository
import com.monithome.presentation.dashboard.DashboardState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class StatsHandler(
    private val pluginRepository: PluginRepository
) {
    fun observeStats(configs: List<PluginInfo>): Flow<Map<String, Map<String, Any>>> {
        val activeIds = configs.filter { it.active }.map { it.id }
        if (activeIds.isEmpty()) return kotlinx.coroutines.flow.flowOf(emptyMap())

        val flows = activeIds.map { id -> 
            pluginRepository.getPluginStats(id).map { id to it } 
        }

        return combine(flows) { pairs -> pairs.toMap() }
    }

    fun updateHistory(s: DashboardState, allStats: Map<String, Map<String, Any>>): DashboardState {
        val systemStats = allStats["system_stats"]
        val newCpuHistory = if (systemStats != null) {
            val cpu = (systemStats["cpu"] as? Number)?.toFloat() ?: 0f
            (s.cpuHistory + cpu).takeLast(50)
        } else s.cpuHistory

        val newCpuTempHistory = if (systemStats != null) {
            val temp = (systemStats["cpu_temp"] as? Number)?.toFloat() ?: 0f
            (s.cpuTempHistory + temp).takeLast(50)
        } else s.cpuTempHistory

        val newGpuLoadHistory = if (systemStats != null) {
            val load = (systemStats["gpu_load"] as? Number)?.toFloat() ?: 0f
            (s.gpuLoadHistory + load).takeLast(50)
        } else s.gpuLoadHistory

        val newGpuTempHistory = if (systemStats != null) {
            val temp = (systemStats["gpu_temp"] as? Number)?.toFloat() ?: 0f
            (s.gpuTempHistory + temp).takeLast(50)
        } else s.gpuTempHistory

        return s.copy(
            stats = allStats,
            cpuHistory = newCpuHistory,
            cpuTempHistory = newCpuTempHistory,
            gpuLoadHistory = newGpuLoadHistory,
            gpuTempHistory = newGpuTempHistory
        )
    }
}
