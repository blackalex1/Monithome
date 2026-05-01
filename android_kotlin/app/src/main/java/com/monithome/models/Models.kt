package com.monithome.models

import com.google.gson.annotations.SerializedName

/**
 * Модель плагина. Все поля сделаны nullable для предотвращения ошибок парсинга
 * при неполных данных от сервера.
 */
data class PluginInfo(
    @SerializedName("id", alternate = ["plugin_id"]) val id: String? = null,
    val name: String? = "Без названия",
    @SerializedName("name_en") val nameEn: String? = null,
    val type: String? = null,
    val description: String? = null,
    @SerializedName("description_en") val descriptionEn: String? = null,
    val version: String? = null,
    val widgets: List<Widget>? = emptyList(),
    val actions: List<Widget>? = emptyList(),
    val active: Boolean? = false
) {
    fun getLocalizedLabel(): String {
        val lang = com.monithome.data.LanguageManager.currentLanguage.value
        return if (lang == com.monithome.data.AppLanguage.ENGLISH && !nameEn.isNullOrEmpty()) {
            nameEn
        } else {
            name ?: ""
        }
    }

    fun getLocalizedDescription(): String {
        val lang = com.monithome.data.LanguageManager.currentLanguage.value
        return if (lang == com.monithome.data.AppLanguage.ENGLISH && !descriptionEn.isNullOrEmpty()) {
            descriptionEn
        } else {
            description ?: ""
        }
    }
}

data class Widget(
    val id: String? = null,
    val type: String? = "text",
    val label: String? = null,
    @SerializedName("label_en") val labelEn: String? = null,
    @SerializedName("data_key") val dataKey: String? = null,
    val action: String? = null,
    val icon: String? = null,
    val unit: String? = null,
    val color: String? = null,
    @SerializedName("list_key") val listKey: String? = null,
    @SerializedName("children", alternate = ["buttons", "items"]) val children: List<Widget>? = emptyList(),
    @SerializedName("color_ranges") val colorRanges: List<ColorRange>? = emptyList(),
    @SerializedName("need_confirm") val needConfirm: Boolean? = false,
    val condition: String? = null
) {
    fun getLocalizedLabel(): String {
        val lang = com.monithome.data.LanguageManager.currentLanguage.value
        return if (lang == com.monithome.data.AppLanguage.ENGLISH && !labelEn.isNullOrEmpty()) {
            labelEn
        } else {
            label ?: ""
        }
    }
}

data class ColorRange(
    val min: Float? = 0f,
    val max: Float? = 100f,
    val color: String? = "#FFFFFF"
)

data class LyricsData(
    @SerializedName("track_id") val trackId: String? = null,
    val lyrics: String? = null,
    val timings: List<LyricTiming>? = emptyList()
)

data class LyricTiming(
    val time: Long? = 0L,
    val text: String? = ""
)
