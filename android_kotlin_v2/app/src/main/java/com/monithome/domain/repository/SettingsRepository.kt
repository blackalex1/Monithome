package com.monithome.domain.repository

interface SettingsRepository {
    fun getWidgetOrder(): List<String>?
    fun saveWidgetOrder(order: List<String>)
}
