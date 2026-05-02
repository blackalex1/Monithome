package com.monithome.network

/**
 * Конфигурация Яндекс Станции для прямого управления.
 */
data class StationConfig(
    val deviceId: String,
    val token: String,
    val name: String,
    var ip: String? = null,
    val port: Int = 1961
)
