package com.monithome.domain.repository

interface SettingsRepository {
    fun getWidgetOrder(): List<String>?
    fun saveWidgetOrder(order: List<String>)
    fun getThemeColor(): Long
    fun saveThemeColor(color: Long)
    fun getString(key: String, default: String? = null): String?
    fun saveString(key: String, value: String?)
    fun getDeviceId(): String
}
