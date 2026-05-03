package com.monithome.domain.models

data class StationConfig(
    val deviceId: String,
    var ip: String? = null,
    val token: String,
    val name: String = "Яндекс Станция",
    val port: Int = 1961
)
