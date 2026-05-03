package com.monithome.domain.models

data class PluginInfo(
    val id: String,
    val name: String,
    val description: String = "",
    val type: String,
    val active: Boolean
)
