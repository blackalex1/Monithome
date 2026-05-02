package com.monithome.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.InputStream

enum class AppLanguage(val code: String) {
    RUSSIAN("ru"),
    ENGLISH("en")
}

object LanguageManager {
    private val _currentLanguage = MutableStateFlow(AppLanguage.RUSSIAN)
    val currentLanguage = _currentLanguage.asStateFlow()

    private var appContext: Context? = null
    private val _translations = MutableStateFlow(JSONObject())

    fun init(context: Context) {
        appContext = context.applicationContext
        loadTranslations(AppLanguage.RUSSIAN)
    }

    fun setLanguage(language: AppLanguage) {
        android.util.Log.d("LanguageManager", "Setting language to: ${language.code}")
        loadTranslations(language)
    }

    /**
     * Загрузка словаря из assets
     */
    fun loadTranslations(language: AppLanguage) {
        val context = appContext ?: return
        try {
            val fileName = "locales/${language.code}.json"
            android.util.Log.d("LanguageManager", "Loading file: $fileName")
            
            val inputStream: InputStream = context.assets.open(fileName)
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            
            val newDict = JSONObject(jsonString)
            _translations.value = newDict
            _currentLanguage.value = language
            
            android.util.Log.d("LanguageManager", "Successfully loaded ${newDict.length()} keys")
        } catch (e: Exception) {
            android.util.Log.e("LanguageManager", "Failed to load translations: ${e.message}")
        }
    }

    /**
     * Основная функция получения строки по ключу
     */
    fun i18n(key: String, defaultValue: String = ""): String {
        val dict = _translations.value
        return try {
            if (dict.has(key)) {
                dict.getString(key)
            } else {
                defaultValue.ifEmpty { key }
            }
        } catch (e: Exception) {
            defaultValue.ifEmpty { key }
        }
    }
}

/**
 * Глобальный объект для удобного доступа к общим строкам
 */
object Strings {
    val waitingData: String get() = LanguageManager.i18n("waiting_data", "Ожидание данных...")
    val yes: String get() = LanguageManager.i18n("yes", "ДА")
    val cancel: String get() = LanguageManager.i18n("cancel", "ОТМЕНА")
    val confirmAction: String get() = LanguageManager.i18n("confirm_action", "Подтверждение действия")
    val sureExecute: String get() = LanguageManager.i18n("sure_execute", "Вы уверены?")
}
