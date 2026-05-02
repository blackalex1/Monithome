package com.monithome.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Size
import com.monithome.data.PluginRepository
import com.monithome.models.LyricTiming
import com.monithome.models.LyricsData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Composable
fun LyricsFullscreenView(
    pluginId: String,
    deviceId: String,
    onDismiss: () -> Unit
) {
    val lyricsData by PluginRepository.lyrics.map { 
        it[deviceId] ?: it["all"] // FALLBACK: Если для конкретного ID нет текста, берем из "all"
    }.collectAsState(initial = null)
    val pluginStats by PluginRepository.getPluginStats(pluginId).collectAsState()
    
    // ОПТИМИЗАЦИЯ: Универсальный поиск данных (поддержка и списков устройств, и плоских структур)
    val deviceData = remember(pluginStats, deviceId) {
        @Suppress("UNCHECKED_CAST")
        val devices = pluginStats["devices"] as? List<Map<String, Any>>
        if (devices != null) {
            devices.find { it["id"] == deviceId } ?: emptyMap()
        } else {
            // Если списка устройств нет, берем данные из корня плагина (например, pc_media)
            pluginStats
        }
    }
    
    val coverData = deviceData["cover"]?.toString()
    val trackTitle = deviceData["title"]?.toString() 
        ?: deviceData["track_name"]?.toString() 
        ?: "Неизвестный трек"
        
    val trackArtist = deviceData["artist"]?.toString() 
        ?: deviceData["subtitle"]?.toString() 
        ?: deviceData["author"]?.toString() 
        ?: "Неизвестный исполнитель"
    
    val playbackState by remember(pluginId, deviceId) {
        PluginRepository.getPluginStats(pluginId).map { stats ->
            @Suppress("UNCHECKED_CAST")
            val devs = stats["devices"] as? List<Map<String, Any>>
            val target = if (devs != null) {
                devs.find { it["id"] == deviceId } ?: emptyMap()
            } else stats

            Triple(
                (target["progress"] as? Number)?.toDouble() ?: 0.0,
                target["playing"] as? Boolean ?: false,
                (target["local_last_update"] as? Number)?.toDouble() ?: (System.currentTimeMillis() / 1000.0)
            )
        }.distinctUntilChanged()
    }.collectAsState(initial = Triple(0.0, false, 0.0))

    val (baseProgress, isPlaying, lastUpdate) = playbackState
    val currentLanguage by com.monithome.data.LanguageManager.currentLanguage.collectAsState()
    
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val data = lyricsData
        val lyricsText = data?.lyrics ?: ""
        val isLoading = lyricsText == "loading" || lyricsText == "Загрузка..." || lyricsText == "Loading..."
        val hasNoLyrics = data != null && !isLoading && 
                         (lyricsText.isEmpty() || lyricsText == "null") && 
                         data.timings.isNullOrEmpty()

        // 1. ФОН
        LyricsBackground(coverData = coverData, blurEnabled = !hasNoLyrics)

        // 2. ЗАТЕМНЕНИЕ
        val overlayAlpha = remember(hasNoLyrics, isLoading) {
            if (hasNoLyrics) 0.0f else if (isLoading) 0.4f else 0.6f
        }
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = overlayAlpha)))

        // 3. КОНТЕНТ
        if (data == null || isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF38BDF8))
            }
        } else if (hasNoLyrics) {
            // Если текстов реально нет (или ошибка)
            Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Нет текста для этой песни", 
                    color = Color.White.copy(alpha = 0.5f), 
                    textAlign = TextAlign.Center
                )
            }
        } else if (data.timings.isNullOrEmpty()) {
            // ОПТИМИЗАЦИЯ: Если есть текст, но нет таймингов - показываем обычный текст с прокруткой
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp, vertical = 120.dp)
                    .verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = lyricsText,
                    color = Color.White,
                    fontSize = 20.sp,
                    lineHeight = 32.sp,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            LyricsListContent(
                lyricsData = data,
                baseProgress = baseProgress,
                isPlaying = isPlaying,
                lastUpdate = lastUpdate
            )
        }

        LyricsHeader(
            title = trackTitle, 
            artist = trackArtist, 
            hasNoLyrics = hasNoLyrics, 
            currentLanguageCode = currentLanguage.code,
            onDismiss = onDismiss
        )
    }
}

@Composable
fun LyricsBackground(coverData: String?, blurEnabled: Boolean) {
    val context = LocalContext.current
    var backgroundBitmap by remember { mutableStateOf<Bitmap?>(null) }
    
    LaunchedEffect(coverData) {
        if (!coverData.isNullOrEmpty()) {
            android.util.Log.d("LyricsBG", "Processing cover, length: ${coverData.length}")
            try {
                val model = withContext(Dispatchers.Default) {
                    if (coverData.startsWith("http")) coverData
                    else if (coverData.startsWith("//")) "https:$coverData"
                    else {
                        val clean = if (coverData.contains(",")) coverData.substringAfter(",") else coverData
                        try { 
                            android.util.Base64.decode(clean, android.util.Base64.DEFAULT) 
                        } catch (e: Exception) { 
                            android.util.Log.e("LyricsBG", "Base64 decode failed: ${e.message}")
                            null 
                        }
                    }
                }

                if (model != null) {
                    val request = ImageRequest.Builder(context)
                        .data(model)
                        .size(Size.ORIGINAL)
                        .build()
                    
                    val result = (context.imageLoader.execute(request) as? SuccessResult)?.drawable
                    if (result != null) {
                        val source = result.toBitmap()
                        withContext(Dispatchers.Default) {
                            val smallBitmap = Bitmap.createScaledBitmap(source, 100, 100, true)
                            val blurred = blurBitmap(smallBitmap, 15)
                            withContext(Dispatchers.Main) {
                                backgroundBitmap = blurred
                                android.util.Log.d("LyricsBG", "Background bitmap SET successfully")
                            }
                        }
                    } else {
                        android.util.Log.e("LyricsBG", "Coil failed to load drawable from model")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("LyricsBG", "Error processing bg: ${e.message}")
            }
        } else {
            android.util.Log.d("LyricsBG", "Cover data is NULL or EMPTY")
            backgroundBitmap = null
        }
    }

    if (blurEnabled) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (backgroundBitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = backgroundBitmap!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alpha = 0.5f
                )
            } else if (!coverData.isNullOrEmpty()) {
                // FALLBACK: Если размытие еще не готово или не сработало, пробуем показать обычную картинку
                AsyncImage(
                    model = coverData,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alpha = 0.3f
                )
            }
        }
    }
}

@Composable
fun LyricsListContent(
    lyricsData: LyricsData,
    baseProgress: Double,
    isPlaying: Boolean,
    lastUpdate: Double
) {
    val timings = remember(lyricsData.timings) { lyricsData.timings ?: emptyList() }
    if (timings.isEmpty()) return

    val currentIndex by produceState(initialValue = -1, timings, isPlaying, baseProgress, lastUpdate) {
        if (!isPlaying) {
            value = timings.indexOfLast { (it.time ?: 0) <= (baseProgress * 1000).toLong() }
            return@produceState
        }
        while (true) {
            val now = System.currentTimeMillis() / 1000.0
            val currentMs = ((baseProgress + (now - lastUpdate)) * 1000).toLong()
            val newIndex = timings.indexOfLast { (it.time ?: 0) <= currentMs }
            if (newIndex != value) value = newIndex
            delay(250) // Обновляем 4 раза в секунду - идеально для CPU
        }
    }

    val listState = rememberLazyListState()

    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0 && currentIndex < timings.size) {
            listState.animateScrollToItem(currentIndex, -200)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 300.dp, bottom = 400.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        itemsIndexed(timings) { index, item ->
            LyricLine(item.text ?: "", isCurrent = index == currentIndex)
        }
    }
}

@Composable
fun LyricLine(text: String, isCurrent: Boolean) {
    val color by animateColorAsState(
        targetValue = if (isCurrent) Color.White else Color.White.copy(alpha = 0.4f),
        animationSpec = tween(400)
    )
    val scale by animateFloatAsState(
        targetValue = if (isCurrent) 1.1f else 1.0f,
        animationSpec = tween(400)
    )

    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall.copy(
            fontWeight = if (isCurrent) FontWeight.Black else FontWeight.Medium,
            fontSize = if (isCurrent) 24.sp else 18.sp
        ),
        color = color,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp, horizontal = 24.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale)
    )
}

@Composable
fun LyricsHeader(
    title: String, 
    artist: String, 
    hasNoLyrics: Boolean, 
    currentLanguageCode: String,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp, maxLines = 1)
            Text(artist, color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp, maxLines = 1)
        }
        
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.background(Color.White.copy(alpha = 0.1f), CircleShape)
        ) {
            Icon(Icons.Default.Close, contentDescription = null, tint = Color.White)
        }
    }
}

private fun blurBitmap(bitmap: Bitmap, radius: Int): Bitmap {
    if (radius < 1) return bitmap
    val w = bitmap.width
    val h = bitmap.height
    val pix = IntArray(w * h)
    bitmap.getPixels(pix, 0, w, 0, 0, w, h)

    val wh = w * h
    val r = IntArray(wh)
    val g = IntArray(wh)
    val b = IntArray(wh)
    
    // Горизонтальный проход
    for (y in 0 until h) {
        for (x in 0 until w) {
            var rsum = 0L; var gsum = 0L; var bsum = 0L; var count = 0
            for (i in -radius..radius) {
                val xi = x + i
                if (xi in 0 until w) {
                    val p = pix[y * w + xi]
                    rsum += (p and 0xff0000) shr 16
                    gsum += (p and 0x00ff00) shr 8
                    bsum += (p and 0x0000ff)
                    count++
                }
            }
            val idx = y * w + x
            r[idx] = (rsum / count).toInt()
            g[idx] = (gsum / count).toInt()
            b[idx] = (bsum / count).toInt()
        }
    }

    // Вертикальный проход
    for (x in 0 until w) {
        for (y in 0 until h) {
            var rsum = 0L; var gsum = 0L; var bsum = 0L; var count = 0
            for (i in -radius..radius) {
                val yi = y + i
                if (yi in 0 until h) {
                    val idx = yi * w + x
                    rsum += r[idx]
                    gsum += g[idx]
                    bsum += b[idx]
                    count++
                }
            }
            pix[y * w + x] = (0xff000000.toInt() or ((rsum / count).toInt() shl 16) or ((gsum / count).toInt() shl 8) or (bsum / count).toInt())
        }
    }

    val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    result.setPixels(pix, 0, w, 0, 0, w, h)
    return result
}

fun android.graphics.drawable.Drawable.toBitmap(): Bitmap {
    val bitmap = Bitmap.createBitmap(intrinsicWidth, intrinsicHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bitmap
}
