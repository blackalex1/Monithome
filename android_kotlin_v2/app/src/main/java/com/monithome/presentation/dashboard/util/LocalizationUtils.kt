package com.monithome.presentation.dashboard.util

import com.monithome.presentation.dashboard.DashboardState

/**
 * Возвращает переведенную строку из состояния по ключу.
 * Если перевода нет, возвращает default.
 */
fun DashboardState.t(key: String, default: String): String {
    return translations[key] ?: default
}
