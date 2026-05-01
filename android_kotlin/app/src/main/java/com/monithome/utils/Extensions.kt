package com.monithome.utils

import androidx.compose.ui.graphics.Color
import androidx.core.graphics.toColorInt
import com.monithome.ui.MonitTheme

/**
 * Безопасное извлечение значения из статистики плагина.
 * Проверяет наличие ключа display_... перед возвратом основного ключа.
 */
fun Map<String, Any>.resolveStat(key: String, unit: String? = null): String {
    val displayKey = "display_$key"
    val value = (this[displayKey] ?: this[key])?.toString() ?: "0"
    
    // Если есть unit и мы используем сырое значение (не display_), добавляем unit
    return if (unit != null && !this.containsKey(displayKey) && value != "0") {
        "$value$unit"
    } else {
        value
    }
}

/**
 * Парсинг HEX цвета с фолбэком на основную тему
 */
fun String?.toComposeColor(fallback: Color = MonitTheme.Primary): Color {
    if (this.isNullOrEmpty()) return fallback
    return try {
        Color(this.toColorInt())
    } catch (e: Exception) {
        fallback
    }
}
