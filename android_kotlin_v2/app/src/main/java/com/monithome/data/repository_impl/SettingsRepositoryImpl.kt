package com.monithome.data.repository_impl

import android.content.Context
import android.content.SharedPreferences
import com.monithome.domain.repository.SettingsRepository

class SettingsRepositoryImpl(context: Context) : SettingsRepository {
    private val prefs: SharedPreferences = context.getSharedPreferences("monithome_prefs", Context.MODE_PRIVATE)

    override fun getWidgetOrder(): List<String>? {
        val orderString = prefs.getString("widget_order", null)
        return orderString?.split(",")?.filter { it.isNotEmpty() }
    }

    override fun saveWidgetOrder(order: List<String>) {
        val orderString = order.joinToString(",")
        prefs.edit().putString("widget_order", orderString).apply()
    }
}
