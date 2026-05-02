package com.monithome.network

import android.util.Log
import com.monithome.data.PluginRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import android.util.Base64
import java.net.URLEncoder

/**
 * Вспомогательный класс для загрузки текстов песен напрямую из API Яндекса и LRCLIB.
 */
class YandexLyricsFetcher(
    private val client: OkHttpClient,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "YandexLyricsFetcher"
        // Фиксированный ключ из реального приложения для подписи запросов к /lyrics
        private const val LYRICS_SECRET = "p93jhgh689SBReK6ghtw62"
        private const val USER_AGENT = "YandexMusicAndroid/24023621"
    }

    fun fetch(deviceId: String, trackId: String, yandexToken: String?) {
        val token = yandexToken ?: run {
            Log.w(TAG, "Cannot fetch lyrics: yandexToken is null")
            return
        }
        if (trackId.isEmpty()) return
        
        // В Яндексе формат обычно trackId:albumId, берем первую часть (как в logic.py на ПК)
        val cleanTrackId = if (trackId.contains(":")) trackId.split(":")[0] else trackId

        // Сразу ставим статус загрузки
        val loadingData = JSONObject().apply {
            put("device_id", deviceId)
            put("data", JSONObject().apply {
                put("lyrics", "loading")
                put("track_id", trackId)
            })
        }
        PluginRepository.handlePluginEvent("yandex_lyrics", "lyrics", loadingData.toString())

        scope.launch(Dispatchers.IO) {
            try {
                // 1. Пытаемся получить через supplement (тайминги в major)
                Log.d(TAG, "[STEP 1] Checking Yandex Supplement API for $cleanTrackId...")
                var resultData = fetchFromSupplement(cleanTrackId, token)
                var hasTimings = (resultData?.optJSONArray("timings")?.length() ?: 0) > 0
                
                // 2. Если таймингов нет, пробуем зашифрованный эндпоинт /lyrics (LRC файл)
                if (!hasTimings) {
                    val lrcData = fetchFromLyricsSigned(cleanTrackId, token)
                    if (lrcData != null && lrcData.optString("lyrics").contains("[00:")) {
                        resultData = lrcData
                        hasTimings = true
                    } else if (lrcData != null) {
                        Log.d(TAG, "[STEP 2] Signed endpoint returned text but no sync marks")
                        if (resultData == null) resultData = lrcData
                    }
                }

                // 3. Если всё еще нет таймингов, пробуем LRCLIB (как на ПК)
                if (!hasTimings) {
                    val lrclibData = fetchFromLrcLib(cleanTrackId, token)
                    if (lrclibData != null) {
                        val lrclibHasSync = lrclibData.optString("lyrics").contains("[00:")
                        resultData = lrclibData
                        hasTimings = lrclibHasSync
                    }
                }

                if (resultData != null) {
                    val lyricsText = resultData.optString("lyrics")
                    val timings = resultData.optJSONArray("timings")
                    val isLrc = hasTimings || lyricsText.contains("[00:")

                    
                    val lyricsEvent = JSONObject().apply {
                        put("device_id", deviceId)
                        put("data", JSONObject().apply {
                            put("lyrics", lyricsText)
                            put("track_id", trackId)
                            if (hasTimings) put("timings", timings)
                            put("type", if (isLrc) "lrc" else "text")
                        })
                    }
                    PluginRepository.handlePluginEvent("yandex_lyrics", "lyrics", lyricsEvent.toString())
                } else {
                    Log.w(TAG, "[FINISH] No lyrics found anywhere for track $cleanTrackId")
                    val emptyEvent = JSONObject().apply {
                        put("device_id", deviceId)
                        put("data", JSONObject().apply {
                            put("lyrics", "")
                            put("track_id", trackId)
                            put("type", "text")
                        })
                    }
                    PluginRepository.handlePluginEvent("yandex_lyrics", "lyrics", emptyEvent.toString())
                }
            } catch (e: Exception) {
                Log.e(TAG, "[ERROR] Fatal exception during lyrics fetch for $trackId: ${e.message}", e)
            }
        }
    }

    private fun fetchFromSupplement(trackId: String, token: String): JSONObject? {
        val request = Request.Builder()
            .url("https://api.music.yandex.net/tracks/$trackId/supplement")
            .addHeader("Authorization", "OAuth $token")
            .addHeader("X-Yandex-Token", token)
            .addHeader("X-Yandex-Music-Client", USER_AGENT)
            .addHeader("User-Agent", USER_AGENT)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.d(TAG, "[Supplement] HTTP Error: ${response.code}")
                    return null
                }
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val result = json.optJSONObject("result") ?: return null
                val lyrics = result.optJSONObject("lyrics") ?: return null
                
                val text = lyrics.optString("fullLyrics") ?: lyrics.optString("text") ?: ""
                val timings = JSONArray()
                
                val lines = lyrics.optJSONObject("major")?.optJSONArray("lines") ?: lyrics.optJSONArray("lines")
                if (lines != null) {
                    Log.d(TAG, "[Supplement] Found ${lines.length()} lines of markup")
                    for (i in 0 until lines.length()) {
                        val line = lines.getJSONObject(i)
                        val startTime = line.optLong("startTimeMs", -1L).let { 
                            if (it == -1L) line.optLong("time", 0L) else it 
                        }
                        timings.put(JSONObject().apply {
                            put("time", startTime)
                            put("text", line.optString("words", line.optString("text", "")))
                        })
                    }
                } else {
                    Log.d(TAG, "[Supplement] No markup found, only plain text available (length: ${text.length})")
                }
                
                JSONObject().apply {
                    put("lyrics", text)
                    put("timings", timings)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[Supplement] Request error: ${e.message}")
            null
        }
    }

    private fun fetchFromLyricsSigned(trackId: String, token: String): JSONObject? {
        try {
            val ts = System.currentTimeMillis() / 1000
            val signRaw = generateSignature(trackId, ts)
            val sign = URLEncoder.encode(signRaw, "UTF-8")
            
            val url = "https://api.music.yandex.net/tracks/$trackId/lyrics?timeStamp=$ts&sign=$sign"
            Log.d(TAG, "[Signed] Requesting LRC download URL...")
            
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "OAuth $token")
                .addHeader("X-Yandex-Token", token)
                .addHeader("X-Yandex-Music-Client", USER_AGENT)
                .addHeader("User-Agent", USER_AGENT)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.d(TAG, "[Signed] HTTP Error: ${response.code}")
                    return null
                }
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val downloadUrl = json.optJSONObject("result")?.optString("downloadUrl")
                
                if (downloadUrl.isNullOrEmpty()) {
                    Log.d(TAG, "[Signed] No downloadUrl returned for this track")
                    return null
                }
                
                Log.d(TAG, "[Signed] Downloading LRC content from: $downloadUrl")
                val lrcRequest = Request.Builder().url(downloadUrl).build()
                client.newCall(lrcRequest).execute().use { lrcResponse ->
                    if (!lrcResponse.isSuccessful) return null
                    val lrcText = lrcResponse.body?.string() ?: return null
                    return JSONObject().apply {
                        put("lyrics", lrcText)
                        put("timings", JSONArray())
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[Signed] Request error: ${e.message}")
        }
        return null
    }

    private fun fetchFromLrcLib(trackId: String, token: String): JSONObject? {
        try {
            Log.d(TAG, "[LRCLIB] Resolving track metadata via Yandex API...")
            val trackInfoRequest = Request.Builder()
                .url("https://api.music.yandex.net/tracks/$trackId")
                .addHeader("Authorization", "OAuth $token")
                .addHeader("X-Yandex-Token", token)
                .addHeader("X-Yandex-Music-Client", USER_AGENT)
                .addHeader("User-Agent", USER_AGENT)
                .build()

            val trackInfo = client.newCall(trackInfoRequest).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val result = json.optJSONArray("result")?.optJSONObject(0) ?: return null
                val title = result.optString("title")
                val artist = result.optJSONArray("artists")?.optJSONObject(0)?.optString("name")
                if (title.isNullOrEmpty() || artist.isNullOrEmpty()) return null
                Log.d(TAG, "[LRCLIB] Identified: $artist - $title")
                Pair(title, artist)
            } ?: return null

            val titleEncoded = URLEncoder.encode(trackInfo.first, "UTF-8")
            val artistEncoded = URLEncoder.encode(trackInfo.second, "UTF-8")
            val url = "https://lrclib.net/api/get?artist_name=$artistEncoded&track_name=$titleEncoded"
            
            Log.d(TAG, "[LRCLIB] Searching on LRCLIB...")
            val lrcLibRequest = Request.Builder().url(url).build()
            client.newCall(lrcLibRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.d(TAG, "[LRCLIB] Not found or API error (HTTP ${response.code})")
                    return null
                }
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val synced = json.optString("syncedLyrics")
                val plain = json.optString("plainLyrics")
                
                if (synced.isNotEmpty()) {
                    Log.d(TAG, "[LRCLIB] Synced lyrics found!")
                    return JSONObject().apply {
                        put("lyrics", synced)
                        put("timings", JSONArray())
                    }
                } else if (plain.isNotEmpty()) {
                    Log.d(TAG, "[LRCLIB] Only plain lyrics found")
                    return JSONObject().apply {
                        put("lyrics", plain)
                        put("timings", JSONArray())
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[LRCLIB] Request error: ${e.message}")
        }
        return null
    }

    private fun generateSignature(trackId: String, ts: Long): String {
        val data = trackId + ts
        val sha256HMAC = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(LYRICS_SECRET.toByteArray(), "HmacSHA256")
        sha256HMAC.init(secretKey)
        return Base64.encodeToString(sha256HMAC.doFinal(data.toByteArray()), Base64.NO_WRAP).trim()
    }
}
