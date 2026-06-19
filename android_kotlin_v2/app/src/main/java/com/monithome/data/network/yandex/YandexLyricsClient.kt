package com.monithome.data.network.yandex

import android.util.Base64
import android.util.Log
import com.monithome.domain.models.LyricLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class YandexLyricsClient(baseClient: OkHttpClient) {
    private val TAG = "YandexLyricsClient"
    private val client = baseClient.newBuilder()
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val commonClient = baseClient.newBuilder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    suspend fun fetchLyrics(trackId: String, token: String): List<LyricLine> = withContext(Dispatchers.IO) {
        Log.i(TAG, "Fetching lyrics for track: $trackId")
        val rawId = trackId.split(":").first()
        val headers = mapOf(
            "Authorization" to "OAuth $token",
            "X-Yandex-Music-Client" to "YandexMusicAndroid/24023621"
        )

        try {
            // 1. Try Supplement (best for timings)
            val supplement = fetchFromSupplement(rawId, headers)
            if (supplement != null) {
                Log.d(TAG, "Fetched lyrics from Supplement for $rawId")
                return@withContext supplement
            }

            // 2. Try Yandex LRC
            val lrc = fetchFromYandexLrc(rawId, headers)
            if (lrc != null) {
                Log.d(TAG, "Fetched lyrics from Yandex LRC for $rawId")
                return@withContext parseLrc(lrc)
            }

            // 3. Fallback to LRCLIB (via Yandex track info)
            val trackInfo = fetchTrackInfo(rawId, headers)
            if (trackInfo != null) {
                val (artist, title) = trackInfo
                val lrclib = fetchFromLrcLib(artist, title)
                if (lrclib != null) {
                    Log.d(TAG, "Fetched lyrics from LRCLIB for $artist - $title")
                    return@withContext lrclib
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lyrics fetch error for $trackId", e)
        }
        
        return@withContext emptyList()
    }

    suspend fun fetchLyricsBySearch(artist: String, title: String, token: String?): List<LyricLine> = withContext(Dispatchers.IO) {
        if (artist.isEmpty() || title.isEmpty()) return@withContext emptyList()
        Log.i(TAG, "Fetching lyrics by search: $artist - $title")
        
        // 1. Try to find track ID in Yandex first if we have a token
        if (token != null) {
            val trackId = searchTrackInYandex(artist, title, token)
            if (trackId != null) {
                Log.d(TAG, "Search: Found Yandex track ID $trackId for $artist - $title")
                val yandexLyrics = fetchLyrics(trackId, token)
                if (yandexLyrics.isNotEmpty()) {
                    Log.i(TAG, "Search: Found lyrics in Yandex via search for $artist - $title")
                    return@withContext yandexLyrics
                }
                Log.d(TAG, "Search: Yandex has no lyrics for ID $trackId, falling back to LRCLIB")
            } else {
                Log.d(TAG, "Search: No track found in Yandex search for $artist - $title")
            }
        }

        // 2. Fallback to LRCLIB
        val lrclib = fetchFromLrcLib(artist, title)
        if (lrclib == null || lrclib.isEmpty()) {
            Log.w(TAG, "Search: No lyrics found in LRCLIB either for $artist - $title")
        }
        return@withContext lrclib ?: emptyList()
    }

    private fun searchTrackInYandex(artist: String, title: String, token: String): String? {
        val query = "$artist - $title"
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("api.music.yandex.net")
            .addPathSegment("search")
            .addQueryParameter("text", query)
            .addQueryParameter("type", "track")
            .build()

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "OAuth $token")
            .addHeader("X-Yandex-Music-Client", "YandexMusicAndroid/24023621")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body.string()
                val json = JSONObject(body)
                val result = json.optJSONObject("result") ?: return null
                val tracks = result.optJSONObject("tracks") ?: return null
                val items = tracks.optJSONArray("results") ?: return null
                if (items.length() > 0) {
                    return items.getJSONObject(0).optString("id")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Yandex search error: ${e.message}")
        }
        return null
    }

    private fun fetchFromSupplement(trackId: String, headers: Map<String, String>): List<LyricLine>? {
        val request = Request.Builder()
            .url("https://api.music.yandex.net/tracks/$trackId/supplement")
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "Supplement fetch failed for $trackId: ${response.code}")
                return null
            }
            val body = response.body.string()
            val json = JSONObject(body)
            val result = json.optJSONObject("result")
            if (result == null) {
                Log.w(TAG, "Supplement result is null for $trackId")
                return null
            }
            val lyricsObj = result.optJSONObject("lyrics")
            if (lyricsObj == null) {
                Log.d(TAG, "Supplement lyrics object is missing for $trackId (falling back to LRC)")
                return null
            }
            val major = lyricsObj.optJSONObject("major")
            if (major == null) {
                Log.d(TAG, "Supplement major (synced) lyrics missing for $trackId (falling back to LRC)")
                return null
            }
            val lines = major.optJSONArray("lines") ?: return null

            val lyricLines = mutableListOf<LyricLine>()
            for (i in 0 until lines.length()) {
                val line = lines.getJSONObject(i)
                lyricLines.add(LyricLine(
                    timeMs = line.optLong("startTimeMs", 0),
                    text = line.optString("words", "")
                ))
            }
            return lyricLines
        }
    }

    private fun fetchFromYandexLrc(trackId: String, headers: Map<String, String>): String? {
        val ts = System.currentTimeMillis() / 1000
        val signature = signLyrics(trackId, ts)
        val encodedSign = java.net.URLEncoder.encode(signature, "UTF-8")
        val url = "https://api.music.yandex.net/tracks/$trackId/lyrics?timeStamp=$ts&sign=$encodedSign"
        Log.d(TAG, "Fetching LRC from: $url")

        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "LRC fetch failed for $trackId: ${response.code}")
                return null
            }
            val body = response.body.string()
            val downloadUrl = JSONObject(body).optJSONObject("result")?.optString("downloadUrl") ?: return null
            
            val lrcRequest = Request.Builder().url(downloadUrl).build()
            client.newCall(lrcRequest).execute().use { lrcResponse ->
                return lrcResponse.body.string()
            }
        }
    }

    private fun signLyrics(trackId: String, timestamp: Long): String {
        val secret = "p93jhgh689SBReK6ghtw62".toByteArray(StandardCharsets.UTF_8)
        val msg = "$trackId$timestamp".toByteArray(StandardCharsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        val hash = mac.doFinal(msg)
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    private fun parseLrc(lrcText: String): List<LyricLine> {
        if (lrcText.isEmpty()) return emptyList()
        val lines = mutableListOf<LyricLine>()
        val pattern = Regex("\\[(\\d+):(\\d+)(?:\\.(\\d+))?\\](.*)")
        
        lrcText.lines().forEach { line ->
            val match = pattern.find(line)
            if (match != null) {
                val (m, s, msStr, text) = match.destructured
                val ms = msStr.takeIf { it.isNotEmpty() }?.toInt() ?: 0
                val normalizedMs = if (msStr.length == 2) ms * 10 else ms
                val totalMs = (m.toInt() * 60 + s.toInt()) * 1000 + normalizedMs
                lines.add(LyricLine(totalMs.toLong(), text.trim()))
            }

        }
        return lines
    }

    private fun fetchTrackInfo(trackId: String, headers: Map<String, String>): Pair<String, String>? {
        val request = Request.Builder()
            .url("https://api.music.yandex.net/tracks/$trackId")
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body.string()
                val json = JSONObject(body)
                
                // Result can be an object (for /tracks/{id}) or an array (for /tracks)
                var result = json.optJSONObject("result")
                if (result == null) {
                    result = json.optJSONArray("result")?.optJSONObject(0)
                }
                
                if (result == null) return null
                
                val title = result.optString("title")
                val artist = result.optJSONArray("artists")?.optJSONObject(0)?.optString("name") ?: ""
                return if (title.isNotEmpty()) artist to title else null
            }
        } catch (e: Exception) {
            return null
        }
    }

    private fun fetchFromLrcLib(artist: String, title: String): List<LyricLine>? {
        // 1. Сначала пробуем точный поиск (GET)
        val getUrl = HttpUrl.Builder()
            .scheme("https")
            .host("lrclib.net")
            .addPathSegment("api")
            .addPathSegment("get")
            .addQueryParameter("artist_name", artist)
            .addQueryParameter("track_name", title)
            .build()

        val getRequest = Request.Builder()
            .url(getUrl)
            .addHeader("User-Agent", "MonitHome/2.0 (Android; Kotlin; +https://github.com/blackalex1)")
            .build()

        try {
            commonClient.newCall(getRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body.string()
                    val json = JSONObject(body)
                    
                    val synced = if (!json.isNull("syncedLyrics")) json.optString("syncedLyrics") else ""
                    if (synced.isNotEmpty()) {
                        Log.i(TAG, "Found synced lyrics via GET")
                        return parseLrc(synced)
                    }
                    
                    val plain = if (!json.isNull("plainLyrics")) json.optString("plainLyrics") else ""
                    if (plain.isNotEmpty()) {
                        Log.i(TAG, "Found plain lyrics via GET")
                        return listOf(LyricLine(0, plain))
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "LRCLIB GET failed: ${e.message}")
        }

        // 2. Если точный поиск не дал результата - пробуем расширенный поиск (SEARCH)
        val searchUrl = HttpUrl.Builder()
            .scheme("https")
            .host("lrclib.net")
            .addPathSegment("api")
            .addPathSegment("search")
            .addQueryParameter("q", "$artist $title")
            .build()

        val searchRequest = Request.Builder()
            .url(searchUrl)
            .addHeader("User-Agent", "MonitHome/2.0 (Android; Kotlin; +https://github.com/blackalex1)")
            .build()

        try {
            Log.i(TAG, "Fuzzy searching LRCLIB: $artist - $title")
            commonClient.newCall(searchRequest).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body.string()
                val results = JSONArray(body)
                if (results.length() > 0) {
                    // Берем первый результат
                    val first = results.getJSONObject(0)
                    
                    val synced = if (!first.isNull("syncedLyrics")) first.optString("syncedLyrics") else ""
                    if (synced.isNotEmpty()) {
                        Log.i(TAG, "Fuzzy search found synced lyrics")
                        return parseLrc(synced)
                    }
                    
                    val plain = if (!first.isNull("plainLyrics")) first.optString("plainLyrics") else ""
                    if (plain.isNotEmpty()) {
                        Log.i(TAG, "Fuzzy search found plain lyrics")
                        return listOf(LyricLine(0, plain))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "LRCLIB Search failed: ${e.message}")
        }

        return null
    }
}
